package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gpu.agent.v1.AgentGateway.Ack;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.Offer;
import gpu.agent.v1.Session.OfferDeclineReason;
import gpu.agent.v1.Session.OfferReply;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Hosts for tests in any package: enrolled, connected with GPUs reported, and listed. */
public final class TestHosts {

    /** The disk a host keeps free for its owner until the owner says otherwise: 20 GB (ADR 0023). */
    public static final int DEFAULT_KEEP_FREE_MB = 20 * 1024;

    private TestHosts() {}

    /**
     * A connected host: its agent, its open stream, the stream's channel, and a heartbeat every half second
     * that keeps its presence alive, reporting {@code freeDiskMb} (plenty, unless a test says otherwise) and {@code
     * cachedImages} (none, unless a test says otherwise). Disconnect it
     * when done.
     */
    public record ConnectedHost(
            TestAgent agent,
            TestAgent.Stream stream,
            ManagedChannel channel,
            Thread heartbeats,
            java.util.concurrent.atomic.AtomicInteger freeDiskMb,
            java.util.concurrent.atomic.AtomicReference<java.util.List<String>> cachedImages) {

        public UUID id() {
            return agent.hostId;
        }

        public void disconnect() {
            heartbeats.interrupt();
            stream.close();
            channel.shutdownNow();
        }

        /** Replies to an offer, as the agent would. */
        public void reply(String offerId, boolean accepted, OfferDeclineReason reason) {
            stream.send(TestAgent.request()
                    .setOfferReply(OfferReply.newBuilder()
                            .setOfferId(offerId)
                            .setAccepted(accepted)
                            .setDeclineReason(reason))
                    .build());
        }

        /** The next offer this host receives. */
        public Offer awaitOffer(Duration timeout) throws InterruptedException {
            return stream.await(OpenStreamResponse::hasOffer, timeout).getOffer();
        }

        /** The next StartSession this host receives, with its envelope (for the message id). */
        public OpenStreamResponse awaitStart(Duration timeout) throws InterruptedException {
            return stream.await(OpenStreamResponse::hasStartSession, timeout);
        }

        /** Acknowledges a message, as the agent does once it has durably recorded it. */
        public void ack(OpenStreamResponse message) {
            stream.send(TestAgent.request()
                    .setAck(Ack.newBuilder().setAckedMessageId(message.getMessageId()))
                    .build());
        }
    }

    /** Connects, lists at {@code pricePaisePerMin} with an 85 °C cap, and activates a host, ready for work. */
    public static ConnectedHost ready(MockMvc mvc, int grpcPort, JdbcTemplate jdbc, int pricePaisePerMin)
            throws Exception {
        var host = connect(mvc, grpcPort);
        list(mvc, host.agent(), pricePaisePerMin, 85);
        jdbc.update("update hosts.hosts set status = 'active' where id = ?", host.id());
        return host;
    }

    /** Enrols a host, opens its stream with Hello for {@code gpuUuids}, and waits for Welcome. */
    public static ConnectedHost connect(MockMvc mvc, int grpcPort, String... gpuUuids) throws Exception {
        return open(TestAgent.enrol(mvc, grpcPort), grpcPort, "0.1.0", gpuUuids);
    }

    /** Enrols a host whose agent's runner says it uses the abuse blocklist {@code blocklistVersion} (empty for none). */
    public static ConnectedHost connectWithBlocklist(MockMvc mvc, int grpcPort, String blocklistVersion)
            throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var channel = agent.channel(grpcPort);
        var stream = TestAgent.open(channel);
        stream.send(TestAgent.helloWithBlocklist(
                gpu.agent.v1.Telemetry.OperatingSystem.OPERATING_SYSTEM_WINDOWS,
                "0.1.0",
                blocklistVersion,
                TestAgent.GPU_A));
        assertThat(stream.next(Duration.ofSeconds(5)).hasWelcome())
                .as("Welcome")
                .isTrue();
        return new ConnectedHost(
                agent,
                stream,
                channel,
                Thread.ofVirtual().start(() -> {}),
                new java.util.concurrent.atomic.AtomicInteger(TestAgent.FREE_DISK_MB),
                new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of()));
    }

    /** Enrols a host whose agent says it runs {@code agentVersion}, and connects it with one GPU. */
    public static ConnectedHost connectAs(MockMvc mvc, int grpcPort, String agentVersion) throws Exception {
        return open(TestAgent.enrol(mvc, grpcPort), grpcPort, agentVersion);
    }

    /** Disconnects a host and connects its agent again, saying it runs {@code agentVersion}. */
    public static ConnectedHost reconnect(ConnectedHost host, int grpcPort, String agentVersion)
            throws InterruptedException {
        host.disconnect();
        return open(host.agent(), grpcPort, agentVersion);
    }

    private static ConnectedHost open(TestAgent agent, int grpcPort, String agentVersion, String... gpuUuids)
            throws InterruptedException {
        var channel = agent.channel(grpcPort);
        var stream = TestAgent.open(channel);
        stream.send(TestAgent.hello(agentVersion, gpuUuids.length == 0 ? new String[] {TestAgent.GPU_A} : gpuUuids));
        var welcome = stream.next(Duration.ofSeconds(5));
        assertThat(welcome.hasWelcome()).as("Welcome").isTrue();
        var gpu = gpuUuids.length == 0 ? TestAgent.GPU_A : gpuUuids[0];
        var freeDiskMb = new java.util.concurrent.atomic.AtomicInteger(TestAgent.FREE_DISK_MB);
        var cachedImages = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>(java.util.List.of());
        var heartbeats = Thread.ofVirtual().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    stream.send(TestAgent.heartbeat(gpu, 60, 5, freeDiskMb.get(), cachedImages.get()));
                    Thread.sleep(500);
                }
            } catch (InterruptedException | RuntimeException stopped) {
                // Disconnected.
            }
        });
        return new ConnectedHost(agent, stream, channel, heartbeats, freeDiskMb, cachedImages);
    }

    /** Sets the host's listing as its owner. */
    public static void list(MockMvc mvc, TestAgent agent, int pricePaisePerMin, int gpuTempCapC) throws Exception {
        mvc.perform(put("/v1/hosts/" + agent.hostId + "/listing")
                        .header("Authorization", agent.bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchPricePaisePerMin": %d, "gpuTempCapC": %d, "maxConcurrentSessions": 1}
                                """.formatted(pricePaisePerMin, gpuTempCapC)))
                .andExpect(status().isOk());
    }
}
