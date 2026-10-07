package com.idleo.controlplane.sessions.internal;

import static com.idleo.controlplane.sessions.internal.SessionEvent.CANCEL;
import static com.idleo.controlplane.sessions.internal.SessionEvent.COMPLETE;
import static com.idleo.controlplane.sessions.internal.SessionEvent.FINISH;
import static com.idleo.controlplane.sessions.internal.SessionEvent.GAME_LOST;
import static com.idleo.controlplane.sessions.internal.SessionEvent.JOB_FAILED;
import static com.idleo.controlplane.sessions.internal.SessionEvent.LEASE_LAPSED;
import static com.idleo.controlplane.sessions.internal.SessionEvent.LOST_REPEATEDLY;
import static com.idleo.controlplane.sessions.internal.SessionEvent.MATCH;
import static com.idleo.controlplane.sessions.internal.SessionEvent.NO_CANDIDATE;
import static com.idleo.controlplane.sessions.internal.SessionEvent.OFFERS_EXHAUSTED;
import static com.idleo.controlplane.sessions.internal.SessionEvent.OFFER_ACCEPTED;
import static com.idleo.controlplane.sessions.internal.SessionEvent.OFFER_DECLINED;
import static com.idleo.controlplane.sessions.internal.SessionEvent.OUTPUTS_MISSING;
import static com.idleo.controlplane.sessions.internal.SessionEvent.REMATCH;
import static com.idleo.controlplane.sessions.internal.SessionEvent.START;
import static com.idleo.controlplane.sessions.internal.SessionEvent.START_FAILED;
import static com.idleo.controlplane.sessions.internal.SessionState.CANCELLED;
import static com.idleo.controlplane.sessions.internal.SessionState.COMPLETED;
import static com.idleo.controlplane.sessions.internal.SessionState.ENDED_REFUNDED;
import static com.idleo.controlplane.sessions.internal.SessionState.FAILED;
import static com.idleo.controlplane.sessions.internal.SessionState.FINISHING;
import static com.idleo.controlplane.sessions.internal.SessionState.HOST_LOST;
import static com.idleo.controlplane.sessions.internal.SessionState.MATCHED;
import static com.idleo.controlplane.sessions.internal.SessionState.NO_HOST_FREE;
import static com.idleo.controlplane.sessions.internal.SessionState.PROVISIONING;
import static com.idleo.controlplane.sessions.internal.SessionState.REQUESTED;
import static com.idleo.controlplane.sessions.internal.SessionState.RUNNING;

import java.util.Map;
import java.util.Optional;

/**
 * The one table of allowed transitions (technical design lines 225-239, ADR 0015). Every move not in it is
 * refused. Beyond the design's table: a declined or expired offer sends a session back to REQUESTED for the
 * next candidate (ADR 0015); a session no host can take goes from REQUESTED straight to NO_HOST_FREE (ADR
 * 0015); a job that fails while running goes straight to FAILED, and so does one whose outputs never appear
 * (ADR 0016); a host can be lost while provisioning, not only while running, once the agent holds a lease;
 * a lost batch session goes back to REQUESTED, through the scheduler as booking, rather than straight to
 * MATCHED; and one that lost its host too often fails (ADR 0016); a stopped job that fails while checkpointing
 * fails (ADR 0017).
 */
final class SessionTransitions {

    record Key(SessionState from, SessionEvent event) {}

    static final Map<Key, SessionState> TABLE = Map.ofEntries(
            Map.entry(new Key(REQUESTED, MATCH), MATCHED),
            Map.entry(new Key(REQUESTED, NO_CANDIDATE), NO_HOST_FREE),
            Map.entry(new Key(REQUESTED, CANCEL), CANCELLED),
            Map.entry(new Key(MATCHED, OFFER_ACCEPTED), PROVISIONING),
            Map.entry(new Key(MATCHED, OFFER_DECLINED), REQUESTED),
            Map.entry(new Key(MATCHED, OFFERS_EXHAUSTED), NO_HOST_FREE),
            Map.entry(new Key(MATCHED, CANCEL), CANCELLED),
            Map.entry(new Key(PROVISIONING, START), RUNNING),
            Map.entry(new Key(PROVISIONING, START_FAILED), FAILED),
            Map.entry(new Key(PROVISIONING, CANCEL), CANCELLED),
            Map.entry(new Key(PROVISIONING, LEASE_LAPSED), HOST_LOST),
            Map.entry(new Key(RUNNING, LEASE_LAPSED), HOST_LOST),
            Map.entry(new Key(RUNNING, FINISH), FINISHING),
            Map.entry(new Key(RUNNING, JOB_FAILED), FAILED),
            Map.entry(new Key(HOST_LOST, REMATCH), REQUESTED),
            Map.entry(new Key(HOST_LOST, LOST_REPEATEDLY), FAILED),
            Map.entry(new Key(HOST_LOST, GAME_LOST), ENDED_REFUNDED),
            Map.entry(new Key(FINISHING, COMPLETE), COMPLETED),
            Map.entry(new Key(FINISHING, OUTPUTS_MISSING), FAILED),
            Map.entry(new Key(FINISHING, JOB_FAILED), FAILED));

    private SessionTransitions() {}

    static Optional<SessionState> next(SessionState from, SessionEvent event) {
        return Optional.ofNullable(TABLE.get(new Key(from, event)));
    }
}
