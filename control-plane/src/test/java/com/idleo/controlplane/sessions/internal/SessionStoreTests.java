package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Every transition goes through one method: checked against the table, versioned, audited (ADR 0015). */
@ControlPlaneTest
class SessionStoreTests {

    @Autowired
    SessionStore store;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    @Test
    void anAllowedTransitionBumpsTheVersionAndIsAudited() {
        var session = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);
        var host = UUID.randomUUID();

        var outcome = inTransaction(() -> store.transition(
                session, SessionEvent.MATCH, "matched", 0, new SessionStore.HostChange.Assign(host, 2)));

        assertThat(outcome).isEqualTo(SessionStore.Outcome.APPLIED);
        var stored = store.find(session).orElseThrow();
        assertThat(stored.state()).isEqualTo(SessionState.MATCHED);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.hostId()).isEqualTo(host);
        assertThat(stored.pricePaisePerMin()).isEqualTo(2);
        assertThat(store.events(session))
                .extracting(event -> event.fromState() + ">" + event.toState() + ":" + event.reason())
                .containsExactly("null>REQUESTED:requested", "REQUESTED>MATCHED:matched");
    }

    @Test
    void aMoveNotInTheTableIsRefusedAndChangesNothing() {
        var session = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);

        var outcome = inTransaction(
                () -> store.transition(session, SessionEvent.OFFER_ACCEPTED, "accepted", null, SessionStore.KEEP));

        assertThat(outcome).isEqualTo(SessionStore.Outcome.REFUSED);
        assertThat(store.find(session).orElseThrow().state()).isEqualTo(SessionState.REQUESTED);
        assertThat(store.events(session)).hasSize(1);
    }

    @Test
    void aStaleVersionIsRefused() {
        var session = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);

        var outcome = inTransaction(() -> store.transition(
                session, SessionEvent.MATCH, "matched", 7, new SessionStore.HostChange.Assign(UUID.randomUUID(), 2)));

        assertThat(outcome).isEqualTo(SessionStore.Outcome.STALE);
        assertThat(store.find(session).orElseThrow().version()).isZero();
    }

    @Test
    void transitionsOnlyRunInsideATransaction() {
        var session = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);
        assertThatThrownBy(() -> store.transition(session, SessionEvent.CANCEL, "x", null, SessionStore.KEEP))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void twoLiveSessionsOnOneHostAreRefusedByTheDatabase() {
        var host = UUID.randomUUID();
        var first = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);
        var second = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);
        inTransaction(() -> store.transition(
                first, SessionEvent.MATCH, "matched", null, new SessionStore.HostChange.Assign(host, 2)));

        assertThatThrownBy(() -> inTransaction(() -> store.transition(
                        second, SessionEvent.MATCH, "matched", null, new SessionStore.HostChange.Assign(host, 2))))
                .hasMessageContaining("one_live_session_per_host");
    }

    @Test
    void sessionEventsAreAppendOnly() {
        var session = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 2, 10_000);
        assertThatThrownBy(() ->
                        jdbc.update("update sessions.session_events set reason = 'x' where session_id = ?", session))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from sessions.session_events where session_id = ?", session))
                .hasMessageContaining("append-only");
    }
}
