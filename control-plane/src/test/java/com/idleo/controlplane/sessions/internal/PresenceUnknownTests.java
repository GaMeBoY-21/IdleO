package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.hosts.HostPresence;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Presence that couldn't be read is unknown, never offline (AgentPresence.Lookup): a booking made while Valkey doesn't
 * answer waits and is tried again (SessionMatcher), and only a lookup that found no host ends it without one.
 */
@ControlPlaneTest
@Import(PresenceUnknownTests.FlakyPresenceConfiguration.class)
class PresenceUnknownTests {

    /** The real presence, except that the next {@code failures} lookups can't be read, as when Valkey times out. */
    static final class FlakyPresence implements AgentPresence {

        final AtomicInteger failures = new AtomicInteger();
        private final AgentPresence real;

        FlakyPresence(AgentPresence real) {
            this.real = real;
        }

        @Override
        public Lookup lookup(Collection<UUID> hostIds) {
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return new Lookup(Map.of(), false);
            }
            return real.lookup(hostIds);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyPresenceConfiguration {

        /** The real presence is the agent gateway's Presence, which is the hosts' HostPresence too. */
        @Bean
        @Primary
        FlakyPresence flakyPresence(HostPresence real) {
            return new FlakyPresence((AgentPresence) real);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FlakyPresence presence;

    @LocalGrpcServerPort
    int grpcPort;

    private TestBookings bookings;

    @BeforeEach
    void bookings() {
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void disconnect() {
        presence.failures.set(0);
        bookings.close();
    }

    @Test
    void aBookingMadeWhilePresenceCantBeReadStillGetsItsHost() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();

        presence.failures.set(1);
        var session = bookings.book(renter);

        assertThat(host.awaitOffer(TestBookings.WAIT)).isNotNull();
        assertThat(bookings.session(renter, session).get("state").asString()).isEqualTo("MATCHED");
    }

    @Test
    void presenceUnknownThroughEveryQuickRetryLeavesTheBookingToTheRematchLoop() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();

        // The first try and all four quick retries (1 + 2 + 4 + 8 s) can't read presence.
        presence.failures.set(5);
        var session = bookings.book(renter);
        Thread.sleep(Duration.ofSeconds(16));
        assertThat(bookings.session(renter, session).get("state").asString())
                .as("still waiting, never ended for want of a host")
                .isEqualTo("REQUESTED");

        // Once it's been waiting 20 s, the re-match loop finds the host.
        assertThat(host.awaitOffer(Duration.ofSeconds(20))).isNotNull();
    }

    /**
     * The first try and three quick retries can't read presence, so the matcher decides at its fourth retry, some 15 s
     * after the booking: later than {@link TestBookings#WAIT}, as when Valkey keeps missing its timeout on a busy runner.
     * The tests' waits follow the matcher, not the clock (TestBookings.untilMatcherDecides).
     */
    @Test
    void aWaitForASessionsStateLastsWhileTheMatcherCantReadPresence() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();

        presence.failures.set(4);
        var session = bookings.book(renter);
        long started = System.nanoTime();
        bookings.awaitState(renter, session, "MATCHED");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(TestBookings.WAIT);
        host.reply(bookings.awaitOffer(host).getOfferId(), true, TestBookings.ACCEPT);
    }

    /** As above, for an offer the host waits for. */
    @Test
    void aWaitForAnOfferLastsWhileTheMatcherCantReadPresence() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();

        presence.failures.set(4);
        bookings.book(renter);
        long started = System.nanoTime();
        var offer = bookings.awaitOffer(host);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(TestBookings.WAIT);
        host.reply(offer.getOfferId(), true, TestBookings.ACCEPT);
    }

    @Test
    void aLookupThatFindsNoHostOnlineStillEndsTheBooking() throws Exception {
        var renter = bookings.renter();

        var session = bookings.book(renter);

        bookings.awaitState(renter, session, "NO_HOST_FREE");
        assertThat(jdbc.queryForObject(
                        "select reason from sessions.session_events where session_id = ? and to_state = 'NO_HOST_FREE'",
                        String.class,
                        session))
                .isEqualTo("no_candidate");
    }
}
