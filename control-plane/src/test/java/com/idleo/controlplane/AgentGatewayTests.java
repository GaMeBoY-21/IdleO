package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gpu.agent.v1.AgentGateway.RenewCertificateRequest;
import gpu.agent.v1.AgentGatewayServiceGrpc;
import gpu.agent.v1.Session.UsageReport;
import gpu.agent.v1.Telemetry.OperatingSystem;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The agent's live stream: mutual TLS, Hello and Welcome, heartbeats and presence (ADR 0011). */
@ControlPlaneTest
class AgentGatewayTests {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void cleanUp() {
        clock.reset();
        channels.forEach(ManagedChannel::shutdownNow);
    }

    @Test
    void anEnrolledAgentConnectsSaysHelloGetsWelcomeAndHeartbeatsKeepItsPresenceAlive() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));

        long opened = System.nanoTime();
        stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A, TestAgent.GPU_B));
        var welcome = stream.next(WAIT);
        long welcomeMillis = (System.nanoTime() - opened) / 1_000_000;

        assertThat(UUID.fromString(welcome.getMessageId()).version()).isEqualTo(7);
        assertThat(welcome.getWelcome().getPolicy().getHeartbeatInterval().getSeconds())
                .isEqualTo(5);
        assertThat(welcome.getWelcome().getPolicy().getGpuTempCapC()).isEqualTo(85);
        assertThat(welcome.getWelcome().getPolicy().getMaxConcurrentSessions()).isEqualTo(1);
        assertThat(welcome.getWelcome().getMinimumAgentVersion()).isEqualTo("0.1.0");
        assertThat(jdbc.queryForList(
                        "select gpu_uuid from hosts.host_gpus where host_id = ? and gone_at is null order by gpu_uuid",
                        String.class,
                        agent.hostId))
                .containsExactly(TestAgent.GPU_A, TestAgent.GPU_B);
        assertThat(jdbc.queryForMap("select os, agent_version from hosts.hosts where id = ?", agent.hostId))
                .containsEntry("os", "windows")
                .containsEntry("agent_version", "1.2.3");
        var key = "agentgateway:presence:" + agent.hostId;
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(15_000);

        for (int i = 0; i < 3; i++) {
            stream.send(TestAgent.heartbeat(TestAgent.GPU_A, 70 + i, 90));
            Thread.sleep(300);
        }
        var record = redis.<String, String>opsForHash().entries(key);
        assertThat(record).containsEntry("agent_version", "1.2.3").containsEntry("update_required", "false");
        assertThat(json.readTree(record.get("gpus")).get(0).get("temp_c").asInt())
                .isEqualTo(72);
        long lastHeartbeat = System.nanoTime();

        while (redis.hasKey(key)) {
            Thread.sleep(50);
            assertThat(System.nanoTime() - lastHeartbeat)
                    .isLessThan(Duration.ofSeconds(10).toNanos());
        }
        double goneAfter = (System.nanoTime() - lastHeartbeat) / 1e9;

        System.out.println("open stream to Welcome: " + welcomeMillis + " ms");
        System.out.println("presence record: " + record);
        System.out.printf("presence gone %.2f s after the last heartbeat (test TTL 2 s)%n", goneAfter);
        assertThat(goneAfter).isBetween(1.5, 4.0);
        stream.close();
    }

    /** The host view reads presence through the hosts module's port (ADR 0013); a clean close ends it at once. */
    @Test
    void theHostShowsOnlineWithItsGpusWhileTheStreamIsOpen() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var host = "/v1/hosts/" + agent.hostId;
        mvc.perform(get(host).header("Authorization", agent.bearer))
                .andExpect(jsonPath("$.online").value(false));

        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
        stream.next(WAIT);

        mvc.perform(get(host).header("Authorization", agent.bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.lastSeenAt").exists())
                .andExpect(jsonPath("$.gpus.length()").value(1))
                .andExpect(jsonPath("$.gpus[0].model").value("NVIDIA GeForce RTX 4080 Laptop GPU"))
                .andExpect(jsonPath("$.gpus[0].vramMb").value(12282))
                .andExpect(jsonPath("$.gpus[0].gone").value(false));
        mvc.perform(get("/v1/hosts").header("Authorization", agent.bearer))
                .andExpect(jsonPath("$.hosts[0].online").value(true));

        stream.close();
        var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (redis.hasKey("agentgateway:presence:" + agent.hostId) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        mvc.perform(get(host).header("Authorization", agent.bearer))
                .andExpect(jsonPath("$.online").value(false));
    }

    @Test
    void anOldAgentIsWelcomedButMarkedUpdateRequired() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.hello("0.0.9", TestAgent.GPU_A));
        stream.next(WAIT);

        assertThat(redis.opsForHash().get("agentgateway:presence:" + agent.hostId, "update_required"))
                .isEqualTo("true");
    }

    @Test
    void noClientCertificateIsRefusedOnTheAgentGatewayButEnrolStillWorks() throws Exception {
        var refusedBefore = counter("agents.connect.refused", "reason", "no_certificate");
        var stream = TestAgent.open(track(TestAgent.channel(grpcPort, null, null)));

        var end = stream.endStatus(WAIT);
        assertThat(end).isNotNull();
        assertThat(end.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(counter("agents.connect.refused", "reason", "no_certificate"))
                .isEqualTo(refusedBefore + 1);
        // Same port, no client certificate: enrolment is open.
        TestAgent.enrol(mvc, grpcPort);
    }

    @Test
    void aCertificateFromAnotherCaFailsTheHandshake() throws Exception {
        var keys = AgentClient.newP256Keys();
        var foreign = TestPki.clientCertificateFromAnotherCa(keys.getPublic(), "urn:idleo:host:" + UUID.randomUUID());
        var stream = TestAgent.open(track(TestAgent.channelForcingCertificate(grpcPort, keys.getPrivate(), foreign)));

        var end = stream.endStatus(WAIT);
        assertThat(end).isNotNull();
        // The handshake fails, so the call never reaches the interceptor.
        assertThat(end.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void aNormalClientDoesNotEvenOfferACertificateFromAnotherCa() throws Exception {
        var keys = AgentClient.newP256Keys();
        var foreign = TestPki.clientCertificateFromAnotherCa(keys.getPublic(), "urn:idleo:host:" + UUID.randomUUID());
        var stream = TestAgent.open(track(TestAgent.channel(grpcPort, keys.getPrivate(), foreign)));

        var end = stream.endStatus(WAIT);
        assertThat(end).isNotNull();
        assertThat(end.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(end.getDescription()).isEqualTo("A client certificate from host enrolment is required.");
    }

    @Test
    void theGrpcPortTrustsOnlyOurRootForClientCertificates() throws Exception {
        // The CA names the server sends when it asks for a client certificate are its trust anchors.
        // Only our root may be there, never the JDK's default cacerts.
        var issuers = new AtomicReference<Principal[]>();
        var keyManager = new X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] acceptable, Socket socket) {
                issuers.set(acceptable);
                return null;
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] acceptable) {
                return new String[0];
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] acceptable) {
                return new String[0];
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] acceptable, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[0];
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return null;
            }
        };
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("root", TestPki.get().root);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var context = SSLContext.getInstance("TLSv1.3");
        context.init(new javax.net.ssl.KeyManager[] {keyManager}, trust.getTrustManagers(), null);
        try (var socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", grpcPort)) {
            socket.startHandshake();
        }

        assertThat(issuers.get()).isNotNull();
        assertThat(issuers.get())
                .extracting(Principal::getName)
                .containsExactly(TestPki.get().root.getSubjectX500Principal().getName());
    }

    @Test
    void aRevokedCertificateIsRefused() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        jdbc.update("update hosts.host_certificates set revoked_at = now() where host_id = ?", agent.hostId);

        assertRefused(agent, Status.Code.UNAUTHENTICATED, "The client certificate is not valid.");
    }

    @Test
    void anExpiredCertificateIsRefused() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        clock.advance(Duration.ofDays(31));

        assertRefused(agent, Status.Code.UNAUTHENTICATED, "The client certificate is not valid.");
    }

    @Test
    void aSuspendedHostIsRefusedWithPermissionDenied() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        jdbc.update("update hosts.hosts set status = 'suspended' where id = ?", agent.hostId);

        assertRefused(agent, Status.Code.PERMISSION_DENIED, "This host is suspended.");
    }

    @Test
    void aCertificateWhoseNameDoesNotMatchItsRowIsRefused() throws Exception {
        var victim = TestAgent.enrol(mvc, grpcPort);
        var attacker = TestAgent.enrol(mvc, grpcPort);
        // Our intermediate signed a certificate naming the victim, but its row says the attacker's host.
        var keys = AgentClient.newP256Keys();
        var mismatched =
                TestPki.get().clientCertificateFromOurIntermediate(keys.getPublic(), "urn:idleo:host:" + victim.hostId);
        jdbc.update(
                """
                insert into hosts.host_certificates
                    (serial, host_id, fingerprint_sha256, not_before, not_after, created_at)
                values (?, ?, ?, ?, ?, now())
                """,
                new java.math.BigDecimal(mismatched.getSerialNumber()),
                attacker.hostId,
                MessageDigest.getInstance("SHA-256").digest(mismatched.getEncoded()),
                new Timestamp(mismatched.getNotBefore().getTime()),
                new Timestamp(mismatched.getNotAfter().getTime()));
        var stream = TestAgent.open(track(TestAgent.channel(
                grpcPort, keys.getPrivate(), new X509Certificate[] {mismatched, TestPki.get().intermediate})));

        var end = stream.endStatus(WAIT);
        assertThat(end).isNotNull();
        assertThat(end.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void theFirstMessageMustBeHello() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.heartbeat(TestAgent.GPU_A, 60, 10));

        assertThat(stream.endStatus(WAIT).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void noHelloInTimeEndsTheStream() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));

        assertThat(stream.endStatus(WAIT).getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    }

    @Test
    void aBadHelloEndsTheStream() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        for (var bad : List.of(
                TestAgent.hello("1.2.3", TestAgent.GPU_A, TestAgent.GPU_A),
                TestAgent.hello("not-a-version", TestAgent.GPU_A),
                TestAgent.hello("1.2.3"))) {
            var stream = TestAgent.open(track(agent.channel(grpcPort)));
            stream.send(bad);
            assertThat(stream.endStatus(WAIT).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    /** The test profile, like local, allows development hosts; production refuses them (AgentMessagesTests). */
    @Test
    void aMacWithAFakeGpuConnectsWhenDevelopmentHostsAreAllowed() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.hello(
                OperatingSystem.OPERATING_SYSTEM_MACOS, "0.1.0", "GPU-00000000-fake-0000-000000000000"));

        assertThat(stream.next(WAIT).hasWelcome()).isTrue();
        assertThat(jdbc.queryForObject("select os from hosts.hosts where id = ?", String.class, agent.hostId))
                .isEqualTo("macos");
    }

    @Test
    void aHeartbeatForAnUnknownGpuIsDroppedAndTheStreamStaysOpen() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
        stream.next(WAIT);
        var invalidBefore = counter("agents.heartbeats.invalid", "reason", "unknown_gpu");

        stream.send(TestAgent.heartbeat("GPU-not-reported", 60, 10));
        stream.send(TestAgent.heartbeat(TestAgent.GPU_A, 61, 10));

        assertThat(stream.endStatus(Duration.ofMillis(800))).isNull();
        assertThat(counter("agents.heartbeats.invalid", "reason", "unknown_gpu"))
                .isEqualTo(invalidBefore + 1);
    }

    @Test
    void aSecondConnectionReplacesTheFirstAndTheFirstsCleanupLeavesThePresenceAlone() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var key = "agentgateway:presence:" + agent.hostId;
        var first = TestAgent.open(track(agent.channel(grpcPort)));
        first.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
        first.next(WAIT);
        var firstConnection = redis.opsForHash().get(key, "connection_id");

        var second = TestAgent.open(track(agent.channel(grpcPort)));
        second.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
        second.next(WAIT);

        var firstEnd = first.endStatus(WAIT);
        assertThat(firstEnd.getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(firstEnd.getDescription()).contains("Replaced by a newer connection");
        Thread.sleep(200);
        var secondConnection = redis.opsForHash().get(key, "connection_id");
        assertThat(secondConnection).isNotNull().isNotEqualTo(firstConnection);
        second.send(TestAgent.heartbeat(TestAgent.GPU_A, 65, 50));
        assertThat(second.endStatus(Duration.ofMillis(500))).isNull();
        assertThat(redis.opsForHash().get(key, "connection_id")).isEqualTo(secondConnection);
    }

    @Test
    void aMessageTypeNotHandledYetIsCountedNotCrashedOn() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
        stream.next(WAIT);
        var before = counter("agents.messages.unhandled", "type", "usage_report");

        stream.send(TestAgent.request()
                .setUsageReport(UsageReport.newBuilder()
                        .setSessionId(UUID.randomUUID().toString())
                        .setAttempt(1)
                        .setMinuteIndex(0))
                .build());
        stream.send(TestAgent.heartbeat(TestAgent.GPU_A, 60, 10));

        assertThat(stream.endStatus(Duration.ofMillis(800))).isNull();
        assertThat(counter("agents.messages.unhandled", "type", "usage_report")).isEqualTo(before + 1);
    }

    @Test
    void theMetersMove() throws Exception {
        var connectedBefore = meters.get("agents.connected").gauge().value();
        var heartbeatsBefore = counter("agents.heartbeats", "outcome", "recorded");
        var closedBefore = counter("agents.streams.closed", "reason", "client_closed");
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
        stream.next(WAIT);
        stream.send(TestAgent.heartbeat(TestAgent.GPU_A, 60, 10));
        Thread.sleep(300);

        assertThat(meters.get("agents.connected").gauge().value()).isEqualTo(connectedBefore + 1);
        assertThat(counter("agents.heartbeats", "outcome", "recorded")).isEqualTo(heartbeatsBefore + 1);
        stream.close();
        Thread.sleep(300);
        assertThat(counter("agents.streams.closed", "reason", "client_closed")).isEqualTo(closedBefore + 1);
        assertThat(meters.get("agents.connected").gauge().value()).isEqualTo(connectedBefore);
    }

    @Test
    void renewalInsideTheWindowIssuesANewCertificateAndKeepsTheOld() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        clock.advance(Duration.ofDays(21));
        var stub = AgentGatewayServiceGrpc.newBlockingStub(track(agent.channel(grpcPort)));

        var renewed = stub.renewCertificate(RenewCertificateRequest.newBuilder()
                .setCsrDer(com.google.protobuf.ByteString.copyFrom(AgentClient.p256Csr(AgentClient.newP256Keys())))
                .build());

        assertThat(renewed.getCertificate().getCertificateChainDerCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ? and revoked_at is null",
                        Integer.class,
                        agent.hostId))
                .isEqualTo(2);
    }

    @Test
    void renewalWorksOnlyFromTheNewestCertificate() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        clock.advance(Duration.ofDays(21));
        var stub = AgentGatewayServiceGrpc.newBlockingStub(track(agent.channel(grpcPort)));
        var request = RenewCertificateRequest.newBuilder()
                .setCsrDer(com.google.protobuf.ByteString.copyFrom(AgentClient.p256Csr(AgentClient.newP256Keys())))
                .build();
        stub.renewCertificate(request);

        var refusal = refusal(() -> stub.renewCertificate(request));

        assertThat(refusal.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(refusal.getDescription())
                .isEqualTo("This certificate was already renewed; use the newer certificate.");
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ?", Integer.class, agent.hostId))
                .isEqualTo(2);
    }

    @Test
    void renewalBeforeTheWindowIsRefusedWithWhenItOpens() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var stub = AgentGatewayServiceGrpc.newBlockingStub(track(agent.channel(grpcPort)));

        var refusal = refusal(() -> stub.renewCertificate(RenewCertificateRequest.newBuilder()
                .setCsrDer(com.google.protobuf.ByteString.copyFrom(AgentClient.p256Csr(AgentClient.newP256Keys())))
                .build()));

        assertThat(refusal.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(refusal.getDescription()).startsWith("Certificate renewal opens at ");
    }

    @Test
    void renewalWithAnRsaKeyIsRefused() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        clock.advance(Duration.ofDays(21));
        var stub = AgentGatewayServiceGrpc.newBlockingStub(track(agent.channel(grpcPort)));
        var rsa = java.security.KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);

        var refusal = refusal(() -> stub.renewCertificate(RenewCertificateRequest.newBuilder()
                .setCsrDer(com.google.protobuf.ByteString.copyFrom(
                        AgentClient.csr(rsa.generateKeyPair(), "SHA256withRSA", null)))
                .build()));

        assertThat(refusal.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private void assertRefused(TestAgent agent, Status.Code code, String description) throws Exception {
        var stream = TestAgent.open(track(agent.channel(grpcPort)));
        var end = stream.endStatus(WAIT);
        assertThat(end).isNotNull();
        assertThat(end.getCode()).isEqualTo(code);
        assertThat(end.getDescription()).isEqualTo(description);
    }

    private static Status refusal(Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException e) {
            return e.getStatus();
        }
        throw new AssertionError("the call was not refused");
    }

    private double counter(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private ManagedChannel track(ManagedChannel channel) {
        channels.add(channel);
        return channel;
    }
}
