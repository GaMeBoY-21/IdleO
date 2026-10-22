package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gpu.agent.v1.Enrollment.EnrollResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Enrolment end to end, over real TLS on the gRPC port, and every way it must refuse. */
@ControlPlaneTest
@ExtendWith(OutputCaptureExtension.class)
class EnrolmentTests {

    private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aSignedInUserRegistersAHostAndItsAgentGetsA30DayCertificate() throws Exception {
        var bearer = bearer();
        long started = System.nanoTime();
        var hostId = registerHost(bearer, "Gaming laptop");
        var code = issueCode(bearer, hostId);
        var agentKeys = AgentClient.newP256Keys();
        EnrollResponse response;
        try (var agent = new AgentClient(grpcPort)) {
            // Typed sloppily on purpose: lower case, no hyphens.
            response = agent.enroll(code.toLowerCase().replace("-", ""), AgentClient.p256Csr(agentKeys));
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        var chain = response.getCertificate().getCertificateChainDerList().stream()
                .map(der -> parse(der.toByteArray()))
                .toList();
        assertThat(chain).hasSize(2);
        var leaf = chain.get(0);
        assertThat(chain.get(1)).isEqualTo(TestPki.get().intermediate);
        validatesToTheTestRoot(chain);
        assertThat(leaf.getPublicKey()).isEqualTo(agentKeys.getPublic());
        assertThat(leaf.getSubjectAlternativeNames()).containsExactly(List.of(6, "urn:idleo:host:" + hostId));
        assertThat(leaf.getExtendedKeyUsage()).containsExactly(CLIENT_AUTH);
        assertThat(leaf.getKeyUsage()).containsExactly(true, false, false, false, false, false, false, false, false);
        assertThat(leaf.getBasicConstraints()).isEqualTo(-1);
        assertThat(Duration.between(
                        leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant()))
                .isEqualTo(Duration.ofDays(30));
        assertThat(leaf.getSerialNumber().bitLength()).isGreaterThanOrEqualTo(64);
        assertThat(response.getCertificate().getNotAfter().getSeconds())
                .isEqualTo(leaf.getNotAfter().toInstant().getEpochSecond());

        var fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ? and fingerprint_sha256 = ?",
                        Integer.class,
                        UUID.fromString(hostId),
                        fingerprint))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select used_at is not null from hosts.enrolment_codes where host_id = ?",
                        Boolean.class,
                        UUID.fromString(hostId)))
                .isTrue();

        System.out.println("enrolment end to end: " + elapsedMillis + " ms (POST /v1/hosts to certificate in hand)");
        System.out.println(TestPki.pem(leaf));
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        var bearer = bearer();
        var code = issueCode(bearer, registerHost(bearer, "Once"));
        try (var agent = new AgentClient(grpcPort)) {
            agent.enroll(code, AgentClient.p256Csr(AgentClient.newP256Keys()));

            assertRefusedCode(() -> agent.enroll(code, AgentClient.p256Csr(AgentClient.newP256Keys())));
        }
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {
        var bearer = bearer();
        var code = issueCode(bearer, registerHost(bearer, "Expires"));
        clock.advance(Duration.ofMinutes(61));
        try (var agent = new AgentClient(grpcPort)) {
            assertRefusedCode(() -> agent.enroll(code, AgentClient.p256Csr(AgentClient.newP256Keys())));
        }
    }

    @Test
    void aReplacedCodeIsRefusedAndTheNewOneWorks() throws Exception {
        var bearer = bearer();
        var hostId = registerHost(bearer, "Replaced");
        var oldCode = issueCode(bearer, hostId);
        var newCode = issueCode(bearer, hostId);
        try (var agent = new AgentClient(grpcPort)) {
            assertRefusedCode(() -> agent.enroll(oldCode, AgentClient.p256Csr(AgentClient.newP256Keys())));
            agent.enroll(newCode, AgentClient.p256Csr(AgentClient.newP256Keys()));
        }
    }

    @Test
    void everyBadCodeGetsTheSameAnswer() throws Exception {
        var bearer = bearer();
        var used = issueCode(bearer, registerHost(bearer, "Used"));
        var expired = issueCode(bearer, registerHost(bearer, "Expired"));
        var csr = AgentClient.p256Csr(AgentClient.newP256Keys());
        try (var agent = new AgentClient(grpcPort)) {
            agent.enroll(used, csr);
            var unknownStatus = refusal(() -> agent.enroll("ABCDE-FGHJK-MNPQR-STVWX", csr));
            var usedStatus = refusal(() -> agent.enroll(used, csr));
            clock.advance(Duration.ofMinutes(61));
            var expiredStatus = refusal(() -> agent.enroll(expired, csr));
            var malformedStatus = refusal(() -> agent.enroll("not-a-code", csr));

            assertThat(unknownStatus.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
            // All four refusals are the same status and the same words.
            assertThat(List.of(usedStatus, expiredStatus, malformedStatus))
                    .extracting(Status::toString)
                    .containsOnly(unknownStatus.toString());
        }
    }

    @Test
    void aCsrWithABadSignatureIsRefused() throws Exception {
        var csr = AgentClient.p256Csr(AgentClient.newP256Keys());
        csr[csr.length - 2] ^= 0x01;
        assertInvalidArgument(csr);
    }

    @Test
    void anRsaKeyIsRefused() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        assertInvalidArgument(AgentClient.csr(generator.generateKeyPair(), "SHA256withRSA", null));
    }

    @Test
    void garbageInsteadOfACsrIsRefused() throws Exception {
        assertInvalidArgument(new byte[] {1, 2, 3, 4});
    }

    @Test
    void emptyFieldsAreRefused() throws Exception {
        try (var agent = new AgentClient(grpcPort)) {
            assertThat(refusal(() -> agent.enroll("", AgentClient.p256Csr(AgentClient.newP256Keys())))
                            .getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(refusal(() -> agent.enroll("ABCDE-FGHJK-MNPQR-STVWX", new byte[0]))
                            .getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    @Test
    void aCsrAskingForAnotherHostStillGetsThisCodesHost() throws Exception {
        var bearer = bearer();
        var thisHost = registerHost(bearer, "This one");
        var otherHost = registerHost(bearer, "Another one");
        var code = issueCode(bearer, thisHost);
        var csr = AgentClient.csr(AgentClient.newP256Keys(), "SHA256withECDSA", "urn:idleo:host:" + otherHost);
        try (var agent = new AgentClient(grpcPort)) {
            var leaf = parse(agent.enroll(code, csr)
                    .getCertificate()
                    .getCertificateChainDer(0)
                    .toByteArray());

            assertThat(leaf.getSubjectAlternativeNames()).containsExactly(List.of(6, "urn:idleo:host:" + thisHost));
            assertThat(leaf.getSubjectX500Principal().getName()).isEmpty();
        }
    }

    @Test
    void twoAgentsRacingOnOneCodeGetExactlyOneCertificate() throws Exception {
        var bearer = bearer();
        var hostId = registerHost(bearer, "Raced");
        var code = issueCode(bearer, hostId);
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<String>();
        try (var agent = new AgentClient(grpcPort);
                var pool = Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<Callable<String>>();
            for (int i = 0; i < 2; i++) {
                calls.add(() -> {
                    var csr = AgentClient.p256Csr(AgentClient.newP256Keys());
                    start.await();
                    try {
                        agent.enroll(code, csr);
                        return "certificate";
                    } catch (StatusRuntimeException e) {
                        return e.getStatus().getCode().name();
                    }
                });
            }
            var futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (var future : futures) {
                outcomes.add(future.get());
            }
        }

        assertThat(outcomes).containsExactlyInAnyOrder("certificate", "UNAUTHENTICATED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ?",
                        Integer.class,
                        UUID.fromString(hostId)))
                .isEqualTo(1);
    }

    @Test
    void theGrpcPortSpeaksOnlyTls13() throws Exception {
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("root", TestPki.get().root);
        trust.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);

        try (var socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", grpcPort)) {
            socket.setEnabledProtocols(new String[] {"TLSv1.3"});
            socket.startHandshake();
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
        }
        try (var socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", grpcPort)) {
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLException.class);
        }
    }

    @Test
    void noCodeCsrOrKeyEverReachesTheLogs(CapturedOutput output) throws Exception {
        var bearer = bearer();
        var code = issueCode(bearer, registerHost(bearer, "Logged"));
        var keys = AgentClient.newP256Keys();
        var csr = AgentClient.p256Csr(keys);
        var badCsr = csr.clone();
        badCsr[badCsr.length - 2] ^= 0x01;
        try (var agent = new AgentClient(grpcPort)) {
            refusal(() -> agent.enroll(code, badCsr));
            agent.enroll(code, csr);
            refusal(() -> agent.enroll(code, csr));
        }

        var canonical = code.replace("-", "");
        assertThat(output.getAll())
                .doesNotContain(code)
                .doesNotContainIgnoringCase(canonical)
                .doesNotContain(Base64.getEncoder().encodeToString(csr))
                .doesNotContainIgnoringCase(HexFormat.of().formatHex(csr))
                .doesNotContain(
                        Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()))
                .doesNotContain("PRIVATE KEY");
    }

    @Test
    void theDatabaseNeverHoldsAPlaintextCode() throws Exception {
        var bearer = bearer();
        var code = issueCode(bearer, registerHost(bearer, "Stored"));
        var tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'hosts'", String.class);

        assertThat(tables).contains("enrolment_codes");
        for (var table : tables) {
            var rows = jdbc.queryForList("select row_to_json(t)::text from hosts." + table + " t", String.class);
            assertThat(String.join("\n", rows))
                    .as("rows of hosts.%s", table)
                    .doesNotContainIgnoringCase(code.replace("-", ""))
                    .doesNotContainIgnoringCase(code);
        }
    }

    private void assertInvalidArgument(byte[] csr) throws Exception {
        var bearer = bearer();
        var code = issueCode(bearer, registerHost(bearer, "Bad CSR"));
        try (var agent = new AgentClient(grpcPort)) {
            assertThat(refusal(() -> agent.enroll(code, csr)).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            // The refusal left the code unused.
            agent.enroll(code, AgentClient.p256Csr(AgentClient.newP256Keys()));
        }
    }

    private static void assertRefusedCode(Runnable call) {
        assertThat(refusal(call).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    private static Status refusal(Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException e) {
            return e.getStatus();
        }
        throw new AssertionError("the call was not refused");
    }

    private String registerHost(String bearer, String name) throws Exception {
        var body = mvc.perform(post("/v1/hosts")
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private String issueCode(String bearer, String hostId) throws Exception {
        var body = mvc.perform(post("/v1/hosts/" + hostId + "/enrolment-code").header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("code").asString();
    }

    private static void validatesToTheTestRoot(List<X509Certificate> chain) throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        var parameters = new PKIXParameters(Set.of(new TrustAnchor(TestPki.get().root, null)));
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), parameters);
    }

    private static X509Certificate parse(byte[] der) {
        try {
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (java.security.cert.CertificateException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new person, signed in, who has accepted the Terms as a person does at their first sign-in. */
    private String bearer() throws Exception {
        return TestUsers.signUp(mvc).bearer();
    }
}
