package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.SessionStatuses;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.sessions.SessionStarted;
import gpu.agent.v1.Session.SessionStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
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

    private static final Logger log = LoggerFactory.getLogger(SessionProgress.class);
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final int BATCH = 50;

    private final SessionStore store;
    private final ApplicationEventPublisher events;
    private final HostLosses losses;
    private final SessionStorage storage;
    private final CreditReservations reservations;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Duration outputsWait;

    SessionProgress(
            SessionStore store,
            ApplicationEventPublisher events,
            HostLosses losses,
            SessionStorage storage,
            CreditReservations reservations,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            @Value("${sessions.outputs-wait}") Duration outputsWait) {
        this.store = store;
        this.events = events;
        this.losses = losses;
        this.storage = storage;
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.outputsWait = outputsWait;
    }

    @Override
    public void onStatus(UUID hostId, SessionStatus status) {
        var sessionId = UUID.fromString(status.getSessionId());
        transactions.executeWithoutResult(tx -> apply(hostId, sessionId, status));
    }

    private void apply(UUID hostId, UUID sessionId, SessionStatus status) {
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
        if (session.state() != SessionState.PROVISIONING && session.state() != SessionState.RUNNING) {
            log.info("Ignored a {} for session {}: it's {}", status.getState(), sessionId, session.state());
            return;
        }
        if (status.getLatestCheckpointSeq() > 0) {
            recordCheckpoint(sessionId, session.attempt(), status.getLatestCheckpointSeq());
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
            case SESSION_STATE_STOPPED -> losses.lost(sessionId, "stopped_on_host");
            default -> {
                // PREPARING changes nothing here.
            }
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
        reservations.release(sessionId);
    }

    /** Records the checkpoint if its completion marker is in storage and it's newer than any recorded. */
    private void recordCheckpoint(UUID sessionId, int attempt, int seq) {
        var known = jdbc.sql("""
                        select exists (select 1 from sessions.checkpoints
                                       where session_id = :id and attempt = :attempt and step >= :seq)
                        """)
                .param("id", sessionId)
                .param("attempt", attempt)
                .param("seq", seq)
                .query(Boolean.class)
                .single();
        if (known) {
            return;
        }
        var folder = StorageLayout.checkpointFolder(sessionId, attempt, seq);
        if (!storage.exists(folder + StorageLayout.COMPLETE)) {
            log.warn(
                    "Session {} attempt {} reported checkpoint {}, but its completion marker isn't in storage; not "
                            + "recorded",
                    sessionId,
                    attempt,
                    seq);
            return;
        }
        jdbc.sql("""
                        insert into sessions.checkpoints (id, session_id, attempt, object_key, step, created_at)
                        values (:id, :sessionId, :attempt, :key, :step, :now)
                        on conflict (session_id, attempt, step) do nothing
                        """)
                .param("id", ids.newId())
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("key", folder)
                .param("step", seq)
                .param("now", Timestamp.from(store.now()))
                .update();
    }

    /**
     * FINISHING sessions whose outputs marker has appeared complete; those still without it after the outputs
     * wait fail with {@code outputs_missing}. Safe with several instances.
     */
    @Scheduled(fixedDelayString = "${sessions.finishing-sweep-interval}")
    void checkFinishing() {
        try {
            transactions.executeWithoutResult(tx -> {
                record Finishing(UUID id, int attempt, java.time.Instant since) {}
                var finishing = jdbc.sql("""
                                select id, attempt, state_changed_at from sessions.sessions
                                where state = 'FINISHING'
                                order by state_changed_at
                                limit :limit
                                for update skip locked
                                """)
                        .param("limit", BATCH)
                        .query((rs, n) -> new Finishing(
                                rs.getObject("id", UUID.class),
                                rs.getInt("attempt"),
                                rs.getTimestamp("state_changed_at").toInstant()))
                        .list();
                for (var session : finishing) {
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
