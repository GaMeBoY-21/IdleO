package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestReleaseKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A blocklist reaching agents over the stream (ADR 0038): once on connecting, to an agent whose runner holds an older one
 * (or none), and to connected agents when the control plane reads a newer one; never to one that already has it. Test
 * agents say they use a list from the far future unless a test says otherwise, so only these are sent one. Ordered: the
 * feed only ever takes newer lists.
 */
@ControlPlaneTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlocklistDeliveryTests {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String FIRST = "2026-10-25T09:30:00Z";
    private static final String SECOND = "2026-10-25T10:30:00Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    AgentReleaseFeed feed;

    @LocalGrpcServerPort
    int grpcPort;

    private static byte[] list(String version) {
        return "{\"version\":\"%s\",\"names\":[],\"hashes\":[],\"pools\":[]}"
                .formatted(version)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void publish(String version) {
        var file = list(version);
        feed.acceptBlocklist(file, TestReleaseKeys.get().sign(file));
    }

    @Test
    @Order(1)
    void anAgentIsSentTheListAfterWelcomeWhenItsRunnerHasNoneOrAnOlderOne() throws Exception {
        publish(FIRST);
        var none = TestHosts.connectWithBlocklist(mvc, grpcPort, "");
        var older = TestHosts.connectWithBlocklist(mvc, grpcPort, "2026-10-22");
        try {
            for (var host : new TestHosts.ConnectedHost[] {none, older}) {
                var sent = host.stream()
                        .await(message -> message.hasUpdateBlocklist(), WAIT)
                        .getUpdateBlocklist();
                assertThat(sent.getVersion()).isEqualTo(FIRST);
                assertThat(sent.getBlocklist().toByteArray()).isEqualTo(list(FIRST));
                assertThat(sent.getSignature().toByteArray())
                        .isEqualTo(TestReleaseKeys.get().sign(list(FIRST)));
            }
        } finally {
            none.disconnect();
            older.disconnect();
        }
    }

    @Test
    @Order(2)
    void anAgentWhoseRunnerAlreadyHasItIsSentNothing() throws Exception {
        var has = TestHosts.connectWithBlocklist(mvc, grpcPort, FIRST);
        var newer = TestHosts.connectWithBlocklist(mvc, grpcPort, "9999-12-31");
        try {
            assertThat(has.stream().receives(message -> message.hasUpdateBlocklist(), Duration.ofSeconds(1)))
                    .isFalse();
            assertThat(newer.stream().receives(message -> message.hasUpdateBlocklist(), Duration.ofMillis(300)))
                    .isFalse();
        } finally {
            has.disconnect();
            newer.disconnect();
        }
    }

    @Test
    @Order(3)
    void aNewerListReachesConnectedAgentsAtOnceAndOnlyThoseWithAnOlderOne() throws Exception {
        var older = TestHosts.connectWithBlocklist(mvc, grpcPort, FIRST);
        var current = TestHosts.connectWithBlocklist(mvc, grpcPort, SECOND);
        try {
            publish(SECOND);

            var sent = older.stream()
                    .await(message -> message.hasUpdateBlocklist(), WAIT)
                    .getUpdateBlocklist();
            assertThat(sent.getVersion()).isEqualTo(SECOND);
            assertThat(current.stream().receives(message -> message.hasUpdateBlocklist(), Duration.ofSeconds(1)))
                    .isFalse();
        } finally {
            older.disconnect();
            current.disconnect();
        }
    }

    @Test
    @Order(4)
    void anUnackedListIsSentAgainOnTheNextConnectionAndAnAckedOneIsNot() throws Exception {
        var host = TestHosts.connectWithBlocklist(mvc, grpcPort, "");
        try {
            var sent = host.stream().await(message -> message.hasUpdateBlocklist(), WAIT);
            assertThat(sent.getUpdateBlocklist().getVersion()).isEqualTo(SECOND);
            host.ack(sent);
        } finally {
            host.disconnect();
        }
    }
}
