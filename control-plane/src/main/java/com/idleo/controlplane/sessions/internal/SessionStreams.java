package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * One live stream per session (ADR 0019): the web app, the CLI and the SDK all follow a session
 * through it, as Server-Sent Events.
 *
 * <ul>
 *   <li>{@code session}: the whole session, built by the same view as {@code GET /v1/sessions/{id}}; sent on
 *       connect, then whenever it changes.
 *   <li>{@code logs}: an attempt and its newest log chunk, whenever a new one exists.
 *   <li>A comment every so often keeps proxies from closing a quiet stream.
 * </ul>
 *
 * It closes after the last {@code session} event once the session is settled, and after a while in any case, so the
 * client reconnects with a fresh token. Changes arrive as hints ({@link SessionHints}); each stream also re-reads on
 * its own timer and sends only what differs, which covers the per-minute accrual, a lost hint and Valkey being down.
 * A renter has at most a few streams open on an instance.
 */
@Component
@EnableConfigurationProperties(SessionStreams.Properties.class)
class SessionStreams implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionStreams.class);

    /**
     * @param maxPerRenter the most streams one renter may have open on an instance
     * @param rereadEvery how often an open stream re-reads its session without a hint
     * @param keepAliveEvery how often a quiet stream sends a comment
     * @param maxOpen how long a stream stays open before it closes and the client reconnects
     */
    @ConfigurationProperties("sessions.streams")
    @Validated
    record Properties(
            int maxPerRenter,
            @NotNull Duration rereadEvery,
            @NotNull Duration keepAliveEvery,
            @NotNull Duration maxOpen) {}

    /** The {@code logs} event: an attempt's newest chunk, for {@code GET /v1/sessions/{id}/logs}. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "SessionLogsEvent")
    record LogsEvent(int attempt, int latestSeq) {}

    private final SessionStore store;
    private final SessionViews views;
    private final SessionLogs logs;
    private final Properties properties;
    private final JsonMapper json;
    private final Set<Stream> open = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "session-streams");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService work = Executors.newVirtualThreadPerTaskExecutor();

    SessionStreams(
            SessionStore store,
            SessionViews views,
            SessionLogs logs,
            SessionHints hints,
            Properties properties,
            JsonMapper json) {
        this.store = store;
        this.views = views;
        this.logs = logs;
        this.properties = properties;
        this.json = json;
        hints.listen(this::wake);
        timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    /** One open stream. Sends are serialised per stream. */
    private final class Stream {
        final UUID renterId;
        final UUID sessionId;
        final SseEmitter emitter;
        /** System.nanoTime() readings: these are timers, not times anything is billed or shown by. */
        final long openedAt;

        volatile long readAt;
        volatile long sentAt;
        String lastSession = "";
        final Map<Integer, Integer> lastLogs = new HashMap<>();
        boolean closed;

        Stream(UUID renterId, UUID sessionId, SseEmitter emitter, long now) {
            this.renterId = renterId;
            this.sessionId = sessionId;
            this.emitter = emitter;
            this.openedAt = now;
            this.readAt = now;
            this.sentAt = now;
        }

        /** Reads the session and sends what changed; closes once it's settled. */
        synchronized void refresh() {
            if (closed) {
                return;
            }
            var now = System.nanoTime();
            readAt = now;
            try {
                var session = store.find(sessionId).orElseThrow();
                var view = views.of(session);
                var body = json.writeValueAsString(view);
                if (!body.equals(lastSession)) {
                    emitter.send(SseEmitter.event().name("session").data(body, MediaType.APPLICATION_JSON));
                    lastSession = body;
                    sentAt = now;
                }
                for (var progress : logs.progress(sessionId)) {
                    if (lastLogs.getOrDefault(progress.attempt(), 0) < progress.latestSeq()) {
                        emitter.send(SseEmitter.event()
                                .name("logs")
                                .data(
                                        json.writeValueAsString(
                                                new LogsEvent(progress.attempt(), progress.latestSeq())),
                                        MediaType.APPLICATION_JSON));
                        lastLogs.put(progress.attempt(), progress.latestSeq());
                        sentAt = now;
                    }
                }
                if (view.settled()) {
                    close();
                }
            } catch (IOException | IllegalStateException gone) {
                // The client went away, or the emitter has already completed.
                drop();
            } catch (RuntimeException e) {
                log.warn("Couldn't refresh the stream of session {}; it tries again shortly", sessionId, e);
            }
        }

        synchronized void keepAlive() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
                sentAt = System.nanoTime();
            } catch (IOException | IllegalStateException gone) {
                drop();
            }
        }

        synchronized void close() {
            if (!closed) {
                closed = true;
                open.remove(this);
                emitter.complete();
            }
        }

        synchronized void drop() {
            closed = true;
            open.remove(this);
        }
    }

    /**
     * Opens a stream of the renter's session; {@code too-many-streams} if they already have the most open. The caller
     * has checked the session is theirs and their account is active.
     */
    SseEmitter open(UUID renterId, UUID sessionId) {
        long theirs =
                open.stream().filter(stream -> stream.renterId.equals(renterId)).count();
        if (theirs >= properties.maxPerRenter()) {
            throw ApiProblem.of(
                    Problem.TOO_MANY_STREAMS,
                    "You have " + theirs + " event streams open, the most at once; close one first.");
        }
        // A little longer than the stream lasts, so it's always closed here, never by the container's timeout.
        var emitter = new SseEmitter(properties.maxOpen().plusMinutes(1).toMillis());
        var stream = new Stream(renterId, sessionId, emitter, System.nanoTime());
        emitter.onCompletion(stream::drop);
        emitter.onTimeout(stream::close);
        emitter.onError(error -> stream.drop());
        open.add(stream);
        work.execute(stream::refresh);
        return emitter;
    }

    /** A hint: refresh this instance's streams of the session now. */
    private void wake(UUID sessionId) {
        for (var stream : open) {
            if (stream.sessionId.equals(sessionId)) {
                work.execute(stream::refresh);
            }
        }
    }

    private void tick() {
        try {
            var now = System.nanoTime();
            for (var stream : open) {
                if (now - stream.openedAt >= properties.maxOpen().toNanos()) {
                    work.execute(stream::close);
                } else if (now - stream.readAt >= properties.rereadEvery().toNanos()) {
                    work.execute(stream::refresh);
                } else if (now - stream.sentAt >= properties.keepAliveEvery().toNanos()) {
                    work.execute(stream::keepAlive);
                }
            }
        } catch (RuntimeException e) {
            log.warn("The stream timer failed; it runs again in a second", e);
        }
    }

    /** How many streams are open on this instance. */
    int openCount() {
        return open.size();
    }

    @Override
    public void destroy() {
        timer.shutdownNow();
        open.forEach(Stream::close);
        work.shutdownNow();
    }
}
