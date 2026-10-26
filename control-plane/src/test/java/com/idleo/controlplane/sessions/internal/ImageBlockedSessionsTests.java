package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.images.ImageBlocked;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A renter's own image blocked after it was booked (ADR 0042): its sessions that haven't started are cancelled,
 * {@code image_blocked}, and one that already ended is left alone.
 */
@ControlPlaneTest
class ImageBlockedSessionsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionStore store;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ImageBlockedSessions listener;

    @Test
    void theSessionsOfABlockedImageThatHaventStartedAreCancelled() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var imageId = UUID.randomUUID();
        var waiting = SessionTestData.requested(store, transactions, renter.id(), 5, 1000);
        var ended = SessionTestData.requested(store, transactions, renter.id(), 5, 1000);
        var other = SessionTestData.requested(store, transactions, renter.id(), 5, 1000);
        jdbc.update("update sessions.sessions set image_id = ? where id in (?, ?)", imageId, waiting, ended);
        jdbc.update("update sessions.sessions set state = 'NO_HOST_FREE' where id = ?", ended);

        listener.on(new ImageBlocked(
                "sha256:" + "a".repeat(64),
                List.of(new ImageBlocked.Blocked(imageId, renter.id(), "ghcr.io/r/j:v1")),
                "blocklisted"));

        // The listener runs after commit, on its own thread, as every module listener does.
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (store.find(waiting).orElseThrow().state() != SessionState.CANCELLED) {
            assertThat(System.nanoTime()).as("the waiting session is cancelled").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(store.events(waiting).getLast().reason()).isEqualTo("image_blocked");
        assertThat(store.find(ended).orElseThrow().state()).isEqualTo(SessionState.NO_HOST_FREE);
        assertThat(store.find(other).orElseThrow().state())
                .as("another image's session")
                .isEqualTo(SessionState.REQUESTED);
    }
}
