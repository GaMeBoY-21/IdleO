package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.SessionStatuses;
import com.idleo.controlplane.sessions.SessionStarted;
import gpu.agent.v1.Session.SessionStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Follows a running attempt from its agent's reports (ADR 0016). Only the session's current attempt on its
 * current host counts; anything else is acked and ignored.
 *
 * <ul>
 *   <li>The first RUNNING moves PROVISIONING to RUNNING and records minute 0 on the control plane's clock.
 *   <li>A checkpoint is recorded only once its {@code _COMPLETE} marker exists in storage.
 *   <li>SUCCEEDED moves RUNNING to FINISHING; the outputs' marker then moves it to COMPLETED, or its absence
 *       after the outputs wait to FAILED with {@code outputs_missing}.
 *   <li>FAILED, or a non-zero exit, moves it to FAILED, keeping the error code.
 *   <li>LEASE_EXPIRED or STOPPED means the host is lost ({@link HostLosses}).
 * </ul>
 *
 * Every end state releases the hold; nothing is charged until metering exists.
 */
@Component
class SessionProgress implements SessionStatuses {

    /** The code the agent gives a STOPPED status when the owner took the machine back, with a checkpoint saved. */
    static final String OWNER_RETURNED = "owner_returned";

    private static final Logger log = LoggerFactory.getLogger(SessionProgress.class);
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final int BATCH = 50;

    private final SessionStore store;
    private final RunningStops stops;
    private final ApplicationEventPublisher events;
    private final HostLosses losses;
    private final SessionStorage storage;
    private final SessionHolds holds;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Checkpoints checkpoints;
    private final SessionLogs logs;
    private final SessionMetrics metrics;
    private final Duration outputsWait;

    SessionProgress(
            SessionStore store,
            RunningStops stops,
            ApplicationEventPublisher events,
            HostLosses losses,
            SessionStorage storage,
            SessionHolds holds,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Checkpoints checkpoints,
            SessionLogs logs,
            SessionMetrics metrics,
            @Value("${sessions.outputs-wait}") Duration outputsWait) {
        this.metrics = metrics;
        this.store = store;
        this.stops = stops;
        this.events = events;
        this.losses = losses;
        this.storage = storage;
        this.holds = holds;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.checkpoints = checkpoints;
        this.logs = logs;
        this.outputsWait = outputsWait;
    }

    @Override
    public void onStatus(UUID hostId, SessionStatus status) {
        var sessionId = UUID.fromString(status.getSessionId());
        transactions.executeWithoutResult(tx -> apply(hostId, sessionId, status));
    }

    private void apply(UUID hostId, UUID sessionId, SessionStatus status) {
        if (status.getLatestLogSeq() > 0) {
            // Logs count from any attempt of the session that ran on this host, the last status of a replaced one too.
            logs.raise(hostId, sessionId, status.getAttempt(), status.getLatestLogSeq());
        }
        var session = store.lock(sessionId).orElse(null);
        if (session == null || !hostId.equals(session.hostId()) || session.attempt() != status.getAttempt()) {
            log.info(
                    "Ignored host {}'s {} for session {} attempt {}: not the session's current attempt there",
                    hostId,
                    status.getState(),
                    sessionId,
                    status.getAttempt());
            return;
        }
        if (session.state() != SessionState.PROVISIONING
                && session.state() != SessionState.RUNNING
                && session.state() != SessionState.FINISHING) {
            log.info("Ignored a {} for session {}: it's {}", status.getState(), sessionId, session.state());
            return;
        }
        if (status.getLatestCheckpointSeq() > 0) {
            checkpoints.record(sessionId, session.attempt(), status.getLatestCheckpointSeq());
        }
        if (session.state() == SessionState.FINISHING) {
            whileFinishing(session, status);
            return;
        }
        switch (status.getState()) {
            case SESSION_STATE_RUNNING, SESSION_STATE_CHECKPOINTING, SESSION_STATE_UPLOADING -> started(session);
            case SESSION_STATE_SUCCEEDED -> {
                if (status.hasExitCode() && status.getExitCode() != 0) {
                    failed(session, "exit_code_" + status.getExitCode());
                } else {
                    started(session);
                    finished(session);
                }
            }
            case SESSION_STATE_FAILED -> failed(session, errorCode(status));
            // The agent fenced the attempt itself, or its owner took the machine back: the host is lost.
            case SESSION_STATE_LEASE_EXPIRED -> losses.lost(sessionId, "lease_expired_on_host");
            case SESSION_STATE_STOPPED ->
                losses.lost(
                        sessionId,
                        OWNER_RETURNED.equals(status.getError().getCode()) ? OWNER_RETURNED : "stopped_on_host");
            default -> {
                // PREPARING changes nothing here.
            }
        }
    }

    /**
     * A report while FINISHING. After a stop (ADR 0017), the attempt ends when its agent reports STOPPED (or its
     * lease ran out, or the job ended anyway), and the session completes with reason {@code stopped}, whatever
     * outputs exist; one that failed is FAILED. After SUCCEEDED, the outputs decide, as before.
     */
    private void whileFinishing(SessionStore.Session session, SessionStatus status) {
        boolean stopping = stops.stopRequested(session.id(), session.attempt());
        switch (status.getState()) {
            case SESSION_STATE_STOPPED, SESSION_STATE_LEASE_EXPIRED, SESSION_STATE_SUCCEEDED -> {
                if (!stopping) {
                    return;
                }
                store.endAttempt(session.id(), session.attempt());
                if (!completeIfOutputsExist(session.id(), session.attempt())) {
                    completeStopped(session.id(), session.attempt());
                }
            }
            case SESSION_STATE_FAILED -> {
                store.endAttempt(session.id(), session.attempt());
                var code = errorCode(status);
                if (store.transition(session.id(), SessionEvent.JOB_FAILED, code, null, SessionStore.KEEP)
                        == SessionStore.Outcome.APPLIED) {
                    end(session.id(), code);
                }
            }
            default -> {
                // Still checkpointing or uploading.
            }
        }
    }

    private void completeStopped(UUID sessionId, int attempt) {
        if (store.transition(sessionId, SessionEvent.COMPLETE, "stopped", null, SessionStore.KEEP)
                == SessionStore.Outcome.APPLIED) {
            end(sessionId, null);
            log.info("Session {} completed on attempt {}, stopped", sessionId, attempt);
        }
    }

    /** The first RUNNING: PROVISIONING to RUNNING, and minute 0 if no attempt ran before. */
    private void started(SessionStore.Session session) {
        if (session.state() != SessionState.PROVISIONING) {
            return;
        }
        if (store.transition(session.id(), SessionEvent.START, "running", null, SessionStore.KEEP)
                != SessionStore.Outcome.APPLIED) {
            return;
        }
        var now = Timestamp.from(store.now());
        if (jdbc.sql("update sessions.sessions set started_at = :now where id = :id and started_at is null")
                        .param("now", now)
                        .param("id", session.id())
                        .update()
                == 1) {
            events.publishEvent(new SessionStarted(session.id()));
            metrics.started();
        } else {
            metrics.resumed();
        }
        jdbc.sql("""
                        update sessions.attempts set running_at = coalesce(running_at, :now)
                        where session_id = :id and attempt = :attempt
                        """)
                .param("now", now)
                .param("id", session.id())
                .param("attempt", session.attempt())
                .update();
        log.info("Session {} attempt {} is running", session.id(), session.attempt());
    }

    private void finished(SessionStore.Session session) {
        store.endAttempt(session.id(), session.attempt());
        if (store.transition(session.id(), SessionEvent.FINISH, "job_succeeded", null, SessionStore.KEEP)
                == SessionStore.Outcome.APPLIED) {
            completeIfOutputsExist(session.id(), session.attempt());
        }
    }

    private void failed(SessionStore.Session session, String errorCode) {
        store.endAttempt(session.id(), session.attempt());
        var event = session.state() == SessionState.PROVISIONING ? SessionEvent.START_FAILED : SessionEvent.JOB_FAILED;
        if (store.transition(session.id(), event, errorCode, null, SessionStore.KEEP) == SessionStore.Outcome.APPLIED) {
            end(session.id(), errorCode);
            log.info("Session {} attempt {} failed: {}", session.id(), session.attempt(), errorCode);
        }
    }

    /** COMPLETED once the attempt's outputs marker exists; true if it moved. */
    private boolean completeIfOutputsExist(UUID sessionId, int attempt) {
        if (!storage.exists(StorageLayout.outputsPrefix(sessionId, attempt) + StorageLayout.COMPLETE)) {
            return false;
        }
        if (store.transition(sessionId, SessionEvent.COMPLETE, "outputs_complete", null, SessionStore.KEEP)
                != SessionStore.Outcome.APPLIED) {
            return false;
        }
        end(sessionId, null);
        log.info("Session {} completed on attempt {}", sessionId, attempt);
        return true;
    }

    /** Every end state: the hold is released and nothing is charged (metering comes next). */
    private void end(UUID sessionId, @org.jspecify.annotations.Nullable String errorCode) {
        if (errorCode != null) {
            jdbc.sql("update sessions.sessions set error_code = :code where id = :id")
                    .param("code", errorCode)
                    .param("id", sessionId)
                    .update();
        }
        holds.releaseUnlessMetered(sessionId);
    }

    /**
     * FINISHING sessions whose outputs marker has appeared complete; those still without it after the outputs
     * wait fail with {@code outputs_missing}. Safe with several instances.
     */
    @Scheduled(fixedDelayString = "${sessions.finishing-sweep-interval}")
    void checkFinishing() {
        try {
            transactions.executeWithoutResult(tx -> {
                record Finishing(
                        UUID id, int attempt, java.time.Instant since, java.time.@Nullable Instant stopDeadline) {}
                var finishing = jdbc.sql("""
                                select s.id, s.attempt, s.state_changed_at, a.stop_deadline
                                from sessions.sessions s
                                left join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                                where s.state = 'FINISHING'
                                order by s.state_changed_at
                                limit :limit
                                for update of s skip locked
                                """)
                        .param("limit", BATCH)
                        .query((rs, n) -> new Finishing(
                                rs.getObject("id", UUID.class),
                                rs.getInt("attempt"),
                                rs.getTimestamp("state_changed_at").toInstant(),
                                rs.getTimestamp("stop_deadline") == null
                                        ? null
                                        : rs.getTimestamp("stop_deadline").toInstant()))
                        .list();
                for (var session : finishing) {
                    if (session.stopDeadline() != null) {
                        // Stopping: done when the agent says so, or once its lease has certainly ended.
                        if (!session.stopDeadline().isAfter(store.now())) {
                            store.endAttempt(session.id(), session.attempt());
                            completeStopped(session.id(), session.attempt());
                        }
                        continue;
                    }
                    if (completeIfOutputsExist(session.id(), session.attempt())) {
                        continue;
                    }
                    if (!session.since().plus(outputsWait).isAfter(store.now())
                            && store.transition(
                                            session.id(),
                                            SessionEvent.OUTPUTS_MISSING,
                                            "outputs_missing",
                                            null,
                                            SessionStore.KEEP)
                                    == SessionStore.Outcome.APPLIED) {
                        end(session.id(), "outputs_missing");
                        log.warn(
                                "Session {} attempt {} succeeded but left no outputs", session.id(), session.attempt());
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warn("The finishing sweep failed; it runs again shortly", e);
        }
    }

    /** The agent's error code if it's a well-formed one; otherwise the exit code, or a generic code. */
    static String errorCode(SessionStatus status) {
        var code = status.getError().getCode().toLowerCase(Locale.ROOT);
        if (ERROR_CODE.matcher(code).matches()) {
            return code;
        }
        if (status.hasExitCode()) {
            return "exit_code_" + status.getExitCode();
        }
        return code.isEmpty() ? "job_failed" : "invalid_error_code";
    }
}
