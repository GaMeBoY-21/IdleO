package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.PersonalDataMask;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard's open streams (ADR 0056): each sends the dashboard at once and every {@code console.rollups.push-every}
 * (10 s), read from the rollups, never worked out per stream. A stream closes after {@code console.rollups.stream-for},
 * so the console reconnects with a session that's still good.
 */
@Component
class ControlRoomStreams implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ControlRoomStreams.class);

    private final ControlRoomView view;
    private final JsonMapper json;
    private final Duration pushEvery;
    private final Duration streamFor;
    private final Set<SseEmitter> open = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "control-room-streams");
        thread.setDaemon(true);
        return thread;
    });

    ControlRoomStreams(
            ControlRoomView view,
            JsonMapper json,
            @Value("${console.rollups.push-every:10s}") Duration pushEvery,
            @Value("${console.rollups.stream-for:10m}") Duration streamFor) {
        this.view = view;
        this.json = json;
        this.pushEvery = pushEvery;
        this.streamFor = streamFor;
    }

    SseEmitter open(UUID admin) {
        var emitter = new SseEmitter(streamFor.toMillis());
        open.add(emitter);
        ScheduledFuture<?>[] pushing = new ScheduledFuture<?>[1];
        Runnable stop = () -> {
            open.remove(emitter);
            if (pushing[0] != null) {
                pushing[0].cancel(false);
            }
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(() -> {
            stop.run();
            emitter.complete();
        });
        emitter.onError(error -> stop.run());
        pushing[0] = timer.scheduleWithFixedDelay(
                () -> push(emitter, admin, stop), 0, pushEvery.toMillis(), TimeUnit.MILLISECONDS);
        return emitter;
    }

    private void push(SseEmitter emitter, UUID admin, Runnable stop) {
        try {
            // As text, through the one mask every admin response passes (ADR 0051), as the session streams send theirs.
            var body = json.writeValueAsString(PersonalDataMask.json(json.valueToTree(view.dashboard(admin))));
            emitter.send(SseEmitter.event().name("dashboard").data(body, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException gone) {
            stop.run();
        } catch (RuntimeException e) {
            log.warn("Couldn't send the dashboard to a stream; it tries again at the next push", e);
        }
    }

    @Override
    public void destroy() {
        open.forEach(SseEmitter::complete);
        timer.shutdownNow();
    }
}
