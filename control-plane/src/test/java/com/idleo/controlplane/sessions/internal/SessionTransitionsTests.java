package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The whole table of allowed transitions, and every move it doesn't allow (ADRs 0015, 0016). */
class SessionTransitionsTests {

    private static final Map<String, SessionState> ALLOWED = Map.ofEntries(
            Map.entry("REQUESTED+MATCH", SessionState.MATCHED),
            Map.entry("REQUESTED+NO_CANDIDATE", SessionState.NO_HOST_FREE),
            Map.entry("REQUESTED+CANCEL", SessionState.CANCELLED),
            Map.entry("MATCHED+OFFER_ACCEPTED", SessionState.PROVISIONING),
            Map.entry("MATCHED+OFFER_DECLINED", SessionState.REQUESTED),
            Map.entry("MATCHED+OFFERS_EXHAUSTED", SessionState.NO_HOST_FREE),
            Map.entry("MATCHED+CANCEL", SessionState.CANCELLED),
            Map.entry("PROVISIONING+START", SessionState.RUNNING),
            Map.entry("PROVISIONING+START_FAILED", SessionState.FAILED),
            Map.entry("PROVISIONING+CANCEL", SessionState.CANCELLED),
            Map.entry("PROVISIONING+LEASE_LAPSED", SessionState.HOST_LOST),
            Map.entry("RUNNING+LEASE_LAPSED", SessionState.HOST_LOST),
            Map.entry("RUNNING+FINISH", SessionState.FINISHING),
            Map.entry("RUNNING+JOB_FAILED", SessionState.FAILED),
            Map.entry("HOST_LOST+REMATCH", SessionState.REQUESTED),
            Map.entry("HOST_LOST+LOST_REPEATEDLY", SessionState.FAILED),
            Map.entry("HOST_LOST+GAME_LOST", SessionState.ENDED_REFUNDED),
            Map.entry("FINISHING+COMPLETE", SessionState.COMPLETED),
            Map.entry("FINISHING+OUTPUTS_MISSING", SessionState.FAILED),
            Map.entry("FINISHING+JOB_FAILED", SessionState.FAILED));

    @Test
    void everyAllowedMoveGoesWhereTheDesignSays() {
        ALLOWED.forEach((move, to) -> {
            var parts = move.split("\\+");
            assertThat(SessionTransitions.next(SessionState.valueOf(parts[0]), SessionEvent.valueOf(parts[1])))
                    .as(move)
                    .contains(to);
        });
        assertThat(SessionTransitions.TABLE).hasSize(ALLOWED.size());
    }

    @Test
    void everyOtherMoveIsRefused() {
        int refused = 0;
        for (var from : SessionState.values()) {
            for (var event : SessionEvent.values()) {
                if (!ALLOWED.containsKey(from + "+" + event)) {
                    assertThat(SessionTransitions.next(from, event))
                            .as(from + "+" + event)
                            .isEmpty();
                    refused++;
                }
            }
        }
        assertThat(refused).isEqualTo(SessionState.values().length * SessionEvent.values().length - ALLOWED.size());
    }

    @Test
    void finalStatesHaveNoWayOut() {
        for (var from : SessionState.values()) {
            if (from.isFinal) {
                for (var event : SessionEvent.values()) {
                    assertThat(SessionTransitions.next(from, event))
                            .as(from + "+" + event)
                            .isEmpty();
                }
            }
        }
        assertThat(SessionState.values()).filteredOn(state -> state.isFinal).hasSize(5);
    }

    @Test
    void aSampleOfRefusals() {
        assertThat(SessionTransitions.next(SessionState.RUNNING, SessionEvent.CANCEL))
                .isEmpty();
        assertThat(SessionTransitions.next(SessionState.REQUESTED, SessionEvent.OFFER_ACCEPTED))
                .isEmpty();
        assertThat(SessionTransitions.next(SessionState.CANCELLED, SessionEvent.MATCH))
                .isEmpty();
        assertThat(SessionTransitions.next(SessionState.PROVISIONING, SessionEvent.MATCH))
                .isEmpty();
        assertThat(SessionTransitions.next(SessionState.NO_HOST_FREE, SessionEvent.CANCEL))
                .isEmpty();
    }
}
