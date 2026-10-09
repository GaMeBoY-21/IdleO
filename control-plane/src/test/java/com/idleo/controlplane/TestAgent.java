package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gpu.agent.v1.AgentGateway.Hello;
import gpu.agent.v1.AgentGateway.OpenStreamRequest;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.AgentGatewayServiceGrpc;
import gpu.agent.v1.Telemetry.GpuInfo;
import gpu.agent.v1.Telemetry.GpuTelemetry;
import gpu.agent.v1.Telemetry.Heartbeat;
import gpu.agent.v1.Telemetry.OperatingSystem;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * A pretend agent for tests: it enrols for real (sign in, register a host, get a code, Enroll) and
 * then talks to AgentGatewayService over mutual TLS with the certificate it was given.
 */
public final class TestAgent {

    public static final String GPU_A = "GPU-11111111-2222-3333-4444-555555555555";
    public static final String GPU_B = "GPU-66666666-7777-8888-9999-000000000000";

    public final UUID hostId;
    public final String bearer;
    final KeyPair keys;
    final X509Certificate[] chain;

    private TestAgent(UUID hostId, String bearer, KeyPair keys, X509Certificate[] chain) {
        this.hostId = hostId;
        this.bearer = bearer;
        this.keys = keys;
        this.chain = chain;
    }

    public static TestAgent enrol(MockMvc mvc, int grpcPort) throws Exception {
        var json = JsonMapper.builder().build();
        var bearer = "Bearer " + TestIssuers.get().validToken();
        var host = mvc.perform(post("/v1/hosts")
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test rig\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var hostId = UUID.fromString(json.readTree(host).get("id").asString());
        var code = json.readTree(mvc.perform(
                                post("/v1/hosts/" + hostId + "/enrolment-code").header("Authorization", bearer))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("code")
                .asString();
        var keys = AgentClient.newP256Keys();
        try (var client = new AgentClient(grpcPort)) {
            var response = client.enroll(code, AgentClient.p256Csr(keys));
            var factory = CertificateFactory.getInstance("X.509");
            var chain = response.getCertificate().getCertificateChainDerList().stream()
                    .map(der -> {
                        try {
                            return (X509Certificate)
                                    factory.generateCertificate(new ByteArrayInputStream(der.toByteArray()));
                        } catch (java.security.cert.CertificateException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toArray(X509Certificate[]::new);
            return new TestAgent(hostId, bearer, keys, chain);
        }
    }

    /** A channel presenting this agent's own certificate. */
    public ManagedChannel channel(int grpcPort) {
        return channel(grpcPort, keys.getPrivate(), chain);
    }

    /** A channel presenting {@code chain} (or no certificate, if null), trusting only our test root. */
    static ManagedChannel channel(int grpcPort, @Nullable PrivateKey key, X509Certificate @Nullable [] chain) {
        try {
            var tls = GrpcSslContexts.forClient().trustManager(TestPki.get().root);
            if (key != null && chain != null) {
                tls.keyManager(key, chain);
            }
            return NettyChannelBuilder.forAddress("localhost", grpcPort)
                    .sslContext(tls.build())
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A channel that presents {@code chain} whatever CAs the server asks for. A normal TLS client only
     * offers a certificate from a CA the server lists, so it would never send a foreign one.
     */
    static ManagedChannel channelForcingCertificate(int grpcPort, PrivateKey key, X509Certificate[] chain) {
        var forcing = new javax.net.ssl.X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(
                    String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) {
                return "agent";
            }

            @Override
            public String chooseEngineClientAlias(
                    String[] keyType, java.security.Principal[] issuers, javax.net.ssl.SSLEngine engine) {
                return "agent";
            }

            @Override
            public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
                return new String[] {"agent"};
            }

            @Override
            public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
                return new String[0];
            }

            @Override
            public @Nullable String chooseServerAlias(
                    String keyType, java.security.Principal[] issuers, java.net.Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return chain.clone();
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
        try {
            return NettyChannelBuilder.forAddress("localhost", grpcPort)
                    .sslContext(GrpcSslContexts.forClient()
                            .trustManager(TestPki.get().root)
                            .keyManager(forcing)
                            .build())
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens OpenStream on the channel; the returned stream collects everything the server sends. */
    public static Stream open(ManagedChannel channel) {
        var stream = new Stream();
        stream.requests = AgentGatewayServiceGrpc.newStub(channel).openStream(stream);
        return stream;
    }

    public static OpenStreamRequest hello(String agentVersion, String... gpuUuids) {
        return hello(OperatingSystem.OPERATING_SYSTEM_WINDOWS, agentVersion, gpuUuids);
    }

    /** The GPU every test agent reports. */
    public static final String GPU_MODEL = "NVIDIA GeForce RTX 4080 Laptop GPU";

    public static OpenStreamRequest hello(OperatingSystem os, String agentVersion, String... gpuUuids) {
        var hello = Hello.newBuilder()
                .setAgentVersion(agentVersion)
                .setOs(os)
                .setOsVersion(os == OperatingSystem.OPERATING_SYSTEM_MACOS ? "macOS 26.0" : "Windows 11 26100")
                .setFreeDiskMb(FREE_DISK_MB);
        for (var uuid : gpuUuids) {
            hello.addGpus(GpuInfo.newBuilder()
                    .setUuid(uuid)
                    .setName(GPU_MODEL)
                    .setVramMb(12282)
                    .setDriverVersion("572.16")
                    .setCudaDriverVersion("12.8")
                    .setComputeCapability("8.9"));
        }
        return request().setHello(hello).build();
    }

    /** What a test host reports free on its disk unless a test says otherwise: plenty for any test's session. */
    public static final int FREE_DISK_MB = 512_000;

    public static OpenStreamRequest heartbeat(String gpuUuid, int tempC, int utilPct) {
        return heartbeat(gpuUuid, tempC, utilPct, FREE_DISK_MB);
    }

    public static OpenStreamRequest heartbeat(String gpuUuid, int tempC, int utilPct, int freeDiskMb) {
        return request()
                .setHeartbeat(Heartbeat.newBuilder()
                        .addGpus(GpuTelemetry.newBuilder()
                                .setUuid(gpuUuid)
                                .setTempC(tempC)
                                .setUtilPct(utilPct)
                                .setVramUsedMb(4096)
                                .setPowerW(95))
                        .setNetUpKbps(20000)
                        .setOwnerActive(false)
                        .setFreeDiskMb(freeDiskMb))
                .build();
    }

    public static OpenStreamRequest.Builder request() {
        return OpenStreamRequest.newBuilder().setMessageId(UUID.randomUUID().toString());
    }

    /** One open stream, as the agent sees it. */
    public static final class Stream implements StreamObserver<OpenStreamResponse> {

        private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        private boolean closed;
        StreamObserver<OpenStreamRequest> requests;

        /** Synchronized: a heartbeat thread and the test may send at the same time. */
        public synchronized void send(OpenStreamRequest request) {
            requests.onNext(request);
        }

        /** The next message matching {@code wanted}, skipping others, or fails if none comes in time. */
        public OpenStreamResponse await(java.util.function.Predicate<OpenStreamResponse> wanted, Duration timeout)
                throws InterruptedException {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                var item = received.poll(20, TimeUnit.MILLISECONDS);
                if (item instanceof OpenStreamResponse response && wanted.test(response)) {
                    return response;
                }
                if (item instanceof Status status) {
                    throw new AssertionError("the stream ended: " + status);
                }
            }
            throw new AssertionError("no matching message within " + timeout);
        }

        /** Whether a message matching {@code wanted} arrives within {@code timeout}. */
        public boolean receives(java.util.function.Predicate<OpenStreamResponse> wanted, Duration timeout)
                throws InterruptedException {
            try {
                await(wanted, timeout);
                return true;
            } catch (AssertionError none) {
                return false;
            }
        }

        /** The next message, or fails if the stream ended or nothing came in time. */
        public OpenStreamResponse next(Duration timeout) throws InterruptedException {
            var item = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (item instanceof OpenStreamResponse response) {
                return response;
            }
            throw new AssertionError("expected a message, got " + item);
        }

        /** How the server ended the stream, or null if it is still open after {@code timeout}. */
        @Nullable
        public Status endStatus(Duration timeout) throws InterruptedException {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                var item = received.poll(10, TimeUnit.MILLISECONDS);
                if (item instanceof Status status) {
                    return status;
                }
            }
            return null;
        }

        /** Forgets everything received so far. */
        public void clear() {
            received.clear();
        }

        /** Half-closes the stream; closing it again does nothing. */
        public synchronized void close() {
            if (!closed) {
                closed = true;
                requests.onCompleted();
            }
        }

        @Override
        public void onNext(OpenStreamResponse response) {
            received.add(response);
        }

        @Override
        public void onError(Throwable error) {
            received.add(Status.fromThrowable(error));
        }

        @Override
        public void onCompleted() {
            received.add(Status.OK);
        }
    }
}
