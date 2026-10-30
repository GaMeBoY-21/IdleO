package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.SessionError;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.webmvc.test.autoconfigure.SpringBootMockMvcBuilderCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two control planes against one PostgreSQL and one Valkey, as the cluster runs them (ADR 0047): each a whole
 * application in this JVM, with its own ports, scheduler, agent streams and event streams.
 *
 * <ul>
 *   <li>a job that must run once runs on one instance at a time, and each such job takes its lock by name;
 *   <li>a host whose stream is on B gets the offer for a booking made on A (ADR 0015), and a renter watching the
 *       session on A sees what its agent reports to B (ADR 0019);
 *   <li>a request limit counts across both, not per instance (ADR 0025);
 *   <li>after Valkey loses its data (a restart), presence is unknown, never offline, until agents heartbeat.
 * </ul>
 */
class TwoInstancesTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int LOG_READS_PER_MINUTE = 10;

    private static final PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();
    private static final GenericContainer<?> valkey = TestcontainersConfiguration.newValkey();
    private static final TestStorageConfiguration.StorageContainer storage =
            new TestStorageConfiguration.StorageContainer();

    private static Instance a;
    private static Instance b;

    /** One control plane: its context, a MockMvc as @AutoConfigureMockMvc builds one, and its gRPC port. */
    record Instance(ConfigurableApplicationContext context, MockMvc mvc, int grpcPort) {

        <T> T bean(Class<T> type) {
            return context.getBean(type);
        }

        /** A bean of a module's internal class, unwrapped from any proxy, for its package-private methods. */
        Object internal(String className) throws ClassNotFoundException {
            return AopTestUtils.getUltimateTargetObject(context.getBean(Class.forName(className)));
        }

        double oneAtATime(String job, String outcome) {
            var counter = bean(MeterRegistry.class)
                    .find("jobs.one_at_a_time")
                    .tag("job", job)
                    .tag("outcome", outcome)
                    .counter();
            return counter == null ? 0 : counter.count();
        }
    }

    @BeforeAll
    static void start() {
        Startables.deepStart(postgres, valkey, storage).join();
        // One after the other: the first migrates the database, as the cluster's migration Job does before any.
        a = start(18195, 19564);
        b = start(18196, 19565);
    }

    @AfterAll
    static void stop() {
        for (var instance : new Instance[] {b, a}) {
            if (instance != null) {
                instance.context().close();
            }
        }
        storage.stop();
        valkey.stop();
        postgres.stop();
    }

    private static Instance start(int scannerPort, int metricsPort) {
        var pki = TestPki.get();
        var settings = new LinkedHashMap<String, Object>();
        settings.put("spring.docker.compose.enabled", "false");
        settings.put("spring.datasource.url", postgres.getJdbcUrl());
        settings.put("spring.datasource.username", postgres.getUsername());
        settings.put("spring.datasource.password", postgres.getPassword());
        settings.put("spring.data.redis.url", "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379));
        settings.put("server.port", "0");
        settings.put("images.scanner.port", scannerPort);
        settings.put("metrics.prometheus.port", metricsPort);
        // Each renter's month is invoiced by a job that must run once (Invoices.monthly).
        settings.put("legal.invoicing.mode", "monthly_usage");
        settings.put("limits.log-reads-per-minute", LOG_READS_PER_MINUTE);
        // TestPkiConfiguration's, TestIssuersConfiguration's (its first issuer), TestStorageConfiguration's and
        // TestRazorpayConfiguration's settings, which a plain application context doesn't read from their registrars.
        settings.put("hosts.ca.root-certificate", pki.rootPem);
        settings.put("hosts.ca.intermediate-certificate", pki.intermediatePem);
        settings.put("hosts.ca.intermediate-private-key", pki.encryptedIntermediateKeyPem);
        settings.put("hosts.ca.intermediate-private-key-password", TestPki.INTERMEDIATE_KEY_PASSWORD);
        settings.put("spring.ssl.bundle.pem.agent-grpc.keystore.certificate", pki.serverChainPem);
        settings.put("spring.ssl.bundle.pem.agent-grpc.keystore.private-key", pki.serverKeyPem);
        settings.put("spring.ssl.bundle.pem.agent-grpc.truststore.certificate", pki.rootPem);
        settings.put("identity.issuers[0].issuer-uri", TestIssuers.get().issuerA);
        settings.put("identity.issuers[0].audience", TestIssuers.AUDIENCE_A);
        settings.put("sessions.storage.provider", "minio");
        settings.put("sessions.storage.endpoint", storage.endpoint());
        settings.put("sessions.storage.bucket", TestStorageConfiguration.BUCKET);
        settings.put("sessions.storage.region", "us-east-1");
        settings.put("sessions.storage.access-key-id", TestStorageConfiguration.ROOT_USER);
        settings.put("sessions.storage.secret-access-key", TestStorageConfiguration.ROOT_PASSWORD);
        settings.put("hosts.releases.endpoint", storage.endpoint());
        settings.put("hosts.releases.bucket", TestStorageConfiguration.RELEASES_BUCKET);
        settings.put("hosts.releases.region", "us-east-1");
        settings.put("hosts.releases.access-key-id", TestStorageConfiguration.ROOT_USER);
        settings.put("hosts.releases.secret-access-key", TestStorageConfiguration.ROOT_PASSWORD);
        settings.put("hosts.releases.public-key", TestReleaseKeys.get().publicKeyBase64());
        settings.put("hosts.releases.refresh-every", "1h");
        settings.put("billing.razorpay.api-url", TestRazorpayConfiguration.RAZORPAY.url());
        settings.put("billing.razorpay.key-id", com.idleo.controlplane.razorpay.FakeRazorpay.KEY_ID);
        settings.put("billing.razorpay.key-secret", com.idleo.controlplane.razorpay.FakeRazorpay.KEY_SECRET);
        settings.put("billing.razorpay.webhook-secret", com.idleo.controlplane.razorpay.FakeRazorpay.WEBHOOK_SECRET);
        settings.put("payouts.razorpayx.api-url", TestRazorpayConfiguration.RAZORPAYX.url());
        settings.put("payouts.razorpayx.key-id", com.idleo.controlplane.razorpay.FakeRazorpayX.KEY_ID);
        settings.put("payouts.razorpayx.key-secret", com.idleo.controlplane.razorpay.FakeRazorpayX.KEY_SECRET);
        settings.put("payouts.razorpayx.webhook-secret", com.idleo.controlplane.razorpay.FakeRazorpayX.WEBHOOK_SECRET);
        settings.put("payouts.razorpayx.account-number", com.idleo.controlplane.razorpay.FakeRazorpayX.ACCOUNT_NUMBER);
        // As command-line arguments, which win over the profiles' files, as the test context's registrars do.
        var context = new SpringApplicationBuilder(ControlPlaneApplication.class, TestClockConfiguration.class)
                .profiles("test")
                .run(settings.entrySet().stream()
                        .map(setting -> "--" + setting.getKey() + "=" + setting.getValue())
                        .toArray(String[]::new));
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context);
        new SpringBootMockMvcBuilderCustomizer((WebApplicationContext) context).customize(builder);
        int grpcPort = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.grpc.server.port"));
        return new Instance(context, builder.build(), grpcPort);
    }

    @Test
    void aJobThatMustRunOnceRunsOnOneInstanceATime() throws Exception {
        // Both instances' ticks at the same moment, each holding its turn a while: one runs, the other skips.
        var ran = new AtomicInteger();
        var together = new CyclicBarrier(2);
        var turns = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try (var both = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var instance : List.of(a, b)) {
                turns.add(both.submit(() -> {
                    together.await();
                    return instance.bean(OneAtATime.class).run("tests:tick", () -> {
                        ran.incrementAndGet();
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }));
            }
        }
        assertThat(turns.stream().filter(turn -> turn.resultNow()).count()).isEqualTo(1);
        assertThat(ran).hasValue(1);
    }

    /** Every job that must run once, by the name it locks and the method its schedule calls. */
    @Test
    void eachJobThatMustRunOnceSkipsItsTurnWhileAnotherInstanceHasIt() throws Exception {
        var retention = ((RetentionJobs) a.internal(RetentionJobs.class.getName()))
                .rules()
                .keySet()
                .iterator()
                .next();
        var jobs = new LinkedHashMap<String, String[]>();
        jobs.put("events:retry", new String[] {EventPublicationRetry.class.getName(), "retry"});
        jobs.put("retention:" + retention, new String[] {RetentionJobs.class.getName(), "runAll"});
        jobs.put(
                "billing:refund-parts", new String[] {"com.idleo.controlplane.billing.internal.CreditRefunds", "send"});
        jobs.put(
                "billing:usage-invoices", new String[] {"com.idleo.controlplane.billing.internal.Invoices", "monthly"});
        jobs.put(
                "payouts:balance-check",
                new String[] {"com.idleo.controlplane.payouts.internal.BalanceCheck", "beforePayday"});
        jobs.put("payouts:method-checks", new String[] {"com.idleo.controlplane.payouts.internal.PayoutMethods", "sweep"
        });
        jobs.put("trust:host-ratings", new String[] {"com.idleo.controlplane.trust.internal.Ratings", "daily"});
        for (Map.Entry<String, String[]> job : jobs.entrySet()) {
            var target = a.internal(job.getValue()[0]);
            var method = job.getValue()[1];
            // As another instance holding it would: the job's lock, on a connection of its own.
            try (var other = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var lock = other.prepareStatement("select pg_advisory_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, job.getKey());
                    lock.execute();
                }
                double ranBefore = a.oneAtATime(job.getKey(), "ran");
                double skippedBefore = a.oneAtATime(job.getKey(), "skipped");
                ReflectionTestUtils.invokeMethod(target, method);
                assertThat(a.oneAtATime(job.getKey(), "ran")).as(job.getKey()).isEqualTo(ranBefore);
                assertThat(a.oneAtATime(job.getKey(), "skipped"))
                        .as(job.getKey())
                        .isGreaterThan(skippedBefore);
                try (var unlock = other.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
                    unlock.setString(1, job.getKey());
                    unlock.execute();
                }
            }
            double ranBefore = a.oneAtATime(job.getKey(), "ran");
            ReflectionTestUtils.invokeMethod(target, method);
            assertThat(a.oneAtATime(job.getKey(), "ran")).as(job.getKey()).isGreaterThan(ranBefore);
        }
    }

    @Test
    void aBookingOnOneInstanceReachesAnAgentOnTheOtherAndItsEventsReachTheRenterWatchingOnTheFirst() throws Exception {
        var jdbc = a.bean(JdbcTemplate.class);
        ConnectedHost host = TestHosts.ready(b.mvc(), b.grpcPort(), jdbc, 2);
        try {
            var renter = TestUsers.signUp(a.mvc());
            TestCredit.grant(a.context(), renter.id(), 50_000);
            var session = book(a.mvc(), renter);

            // The offer, made on A, goes down the stream B holds: its presence names B, which A's hint reaches.
            var offer = offerFor(host, session, jdbc);
            host.reply(offer, true, gpu.agent.v1.Session.OfferDeclineReason.OFFER_DECLINE_REASON_UNSPECIFIED);
            var start = host.awaitStart(WAIT);
            host.ack(start);

            // The renter follows it on A; the agent reports to B.
            var stream = a.mvc()
                    .perform(get("/v1/sessions/" + session + "/events")
                            .header("Authorization", renter.bearer())
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andExpect(status().isOk())
                    .andReturn();
            awaitState(stream, "PROVISIONING");
            report(host, session, SessionStatus.newBuilder().setState(SessionState.SESSION_STATE_RUNNING));
            var reported = Instant.now();
            awaitState(stream, "RUNNING");
            // A hint, not the stream's own re-read, carried it.
            assertThat(Duration.between(reported, Instant.now())).isLessThan(Duration.ofSeconds(3));

            report(
                    host,
                    session,
                    SessionStatus.newBuilder()
                            .setState(SessionState.SESSION_STATE_FAILED)
                            .setError(SessionError.newBuilder().setCode("job_failed")));
            awaitState(stream, "FAILED");
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aRequestLimitHoldsAcrossBothInstances() throws Exception {
        // Fixed one-minute windows: start early enough in one that all the reads fall in it.
        var second = Instant.now().getEpochSecond() % 60;
        if (second > 40) {
            Thread.sleep((61 - second) * 1000);
        }
        var renter = TestUsers.signUp(a.mvc());
        var answered = new ArrayList<Integer>();
        for (int i = 0; i < LOG_READS_PER_MINUTE + 4; i++) {
            var instance = i % 2 == 0 ? a : b;
            answered.add(instance.mvc()
                    .perform(
                            get("/v1/sessions/" + UUID.randomUUID() + "/logs").header("Authorization", renter.bearer()))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }
        assertThat(answered.subList(0, LOG_READS_PER_MINUTE)).doesNotContain(429);
        assertThat(answered.subList(LOG_READS_PER_MINUTE, answered.size())).containsOnly(429);
    }

    @Test
    void afterValkeyLosesItsDataPresenceIsUnknownNeverOfflineUntilAgentsHeartbeat() throws Exception {
        ConnectedHost host = TestHosts.ready(b.mvc(), b.grpcPort(), a.bean(JdbcTemplate.class), 2);
        try {
            var owner = host.agent().bearer;
            assertThat(presence(a, host.id(), owner)).isEqualTo("ONLINE");

            // A restart: Valkey keeps nothing on disk, so it comes back with nothing, the agent still connected.
            a.bean(StringRedisTemplate.class)
                    .execute(
                            connection -> {
                                connection.serverCommands().flushAll();
                                return null;
                            },
                            true);
            assertThat(presence(a, host.id(), owner)).isEqualTo("UNKNOWN");
            assertThat(presence(b, host.id(), owner)).isEqualTo("UNKNOWN");

            // Its heartbeats write its record again; once presence has been kept a TTL, it's read again.
            var deadline = System.nanoTime() + WAIT.toNanos();
            String now;
            do {
                now = presence(a, host.id(), owner);
                assertThat(now).isNotEqualTo("OFFLINE");
                Thread.sleep(100);
            } while (!now.equals("ONLINE") && System.nanoTime() < deadline);
            assertThat(now).isEqualTo("ONLINE");
        } finally {
            host.disconnect();
        }
    }

    private static String presence(Instance instance, UUID hostId, String bearer) throws Exception {
        var body = instance.mvc()
                .perform(get("/v1/hosts/" + hostId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(body).get("presence").asString();
    }

    private static UUID book(MockMvc mvc, TestUser renter) throws Exception {
        var body = mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5,"
                                + " \"maxSpendPaise\": 10000}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    /** This session's offer on this host, declining any other test's session offered first. */
    private static String offerFor(ConnectedHost host, UUID session, JdbcTemplate jdbc) throws InterruptedException {
        while (true) {
            var offerId = host.awaitOffer(WAIT).getOfferId();
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select session_id = ? from scheduling.offers where id = ?",
                    Boolean.class,
                    session,
                    UUID.fromString(offerId)))) {
                return offerId;
            }
            host.reply(
                    offerId,
                    false,
                    gpu.agent.v1.Session.OfferDeclineReason.OFFER_DECLINE_REASON_INSUFFICIENT_RESOURCES);
        }
    }

    private static void report(ConnectedHost host, UUID session, SessionStatus.Builder status)
            throws InterruptedException {
        var request = TestAgent.request()
                .setSessionStatus(status.setSessionId(session.toString()).setAttempt(1))
                .build();
        host.stream().send(request);
        host.stream()
                .await(
                        (OpenStreamResponse message) -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    /** Waits for the stream's newest {@code session} event to be in {@code state}. */
    private static void awaitState(MvcResult stream, String state) throws Exception {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            JsonNode last = null;
            for (var block : stream.getResponse().getContentAsString().split("\n\n")) {
                if (block.contains("event:session")) {
                    for (var line : block.split("\n")) {
                        if (line.startsWith("data:")) {
                            last = JSON.readTree(line.substring(5).strip());
                        }
                    }
                }
            }
            if (last != null && last.get("state").asString().equals(state)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the stream on A never showed " + state + "; it sent "
                        + stream.getResponse().getContentAsString());
            }
            Thread.sleep(50);
        }
    }
}
