package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestReleaseKeys;
import com.idleo.controlplane.TestStorageConfiguration;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Host.HostState;
import gpu.agent.v1.Host.UpdateResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Windows app's release feed end to end (ADR 0034): a manifest published to the releases bucket, signed with the
 * test key, is served by {@code GET /v1/agent-releases/latest} and its setup redirect, and offered in {@code
 * UpdateAgent} only to a host that should have it. Ordered: nothing is published before the first test, and versions
 * only go up, as the feed allows. The test release is 0.0.5, so only these tests' agents (0.0.1) are ever offered it.
 */
@ControlPlaneTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentReleaseTests {

    static final String VERSION = "0.0.5";
    static final String OLD_AGENT = "0.0.1";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final byte[] SETUP = "pretend setup program".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AgentReleaseFeed feed;

    @Autowired
    TestStorageConfiguration.StorageContainer storage;

    @LocalGrpcServerPort
    int grpcPort;

    private final JsonMapper json = JsonMapper.builder().build();

    private S3Client s3() {
        return S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        TestStorageConfiguration.ROOT_USER, TestStorageConfiguration.ROOT_PASSWORD)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /** Publishes a signed manifest (and its files) as {@code publish.sh} does, then has the feed read it. */
    private void publish(String version, int rollout, boolean goodSignature) {
        var manifest = ReleaseManifestTests.manifest(version, rollout).getBytes(StandardCharsets.UTF_8);
        var signature = goodSignature
                ? TestReleaseKeys.get().sign(manifest)
                : TestReleaseKeys.sign(TestReleaseKeys.another(), manifest);
        try (var s3 = s3()) {
            try {
                s3.createBucket(b -> b.bucket(TestStorageConfiguration.RELEASES_BUCKET));
            } catch (BucketAlreadyOwnedByYouException exists) {
                // Published before.
            }
            put(s3, "stable/" + version + "/IdleO-Setup.exe", SETUP);
            put(s3, "stable/" + version + "/idleo-app-" + version + ".zip", "app".getBytes(StandardCharsets.US_ASCII));
            put(s3, "stable/latest.json", manifest);
            put(
                    s3,
                    "stable/latest.json.sig",
                    (Base64.getEncoder().encodeToString(signature) + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        feed.refresh();
    }

    private static void put(S3Client s3, String key, byte[] bytes) {
        s3.putObject(b -> b.bucket(TestStorageConfiguration.RELEASES_BUCKET).key(key), RequestBody.fromBytes(bytes));
    }

    @Test
    @Order(1)
    void withNoReleaseThereIsNothingToDownload() throws Exception {
        feed.refresh();
        mvc.perform(get("/v1/agent-releases/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/no-agent-release"));
        mvc.perform(get("/v1/agent-releases/latest/setup"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/no-agent-release"));
    }

    @Test
    @Order(2)
    void aPublishedReleaseIsServedWithLinksThatDownload() throws Exception {
        publish(VERSION, 0, true);

        var body = json.readTree(mvc.perform(get("/v1/agent-releases/latest"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.version").value(VERSION))
                .andExpect(jsonPath("$.setupSizeBytes").value(9_700_000))
                .andReturn()
                .getResponse()
                .getContentAsString());
        // The manifest comes back exactly as signed, and its signature verifies against the release key.
        var manifest = body.get("manifest").asString().getBytes(StandardCharsets.UTF_8);
        assertThat(manifest).isEqualTo(ReleaseManifestTests.manifest(VERSION, 0).getBytes(StandardCharsets.UTF_8));
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(concat(
                        HexFormat.of().parseHex("302a300506032b6570032100"),
                        Base64.getDecoder().decode(TestReleaseKeys.get().publicKeyBase64())))));
        verifier.update(manifest);
        assertThat(verifier.verify(
                        Base64.getDecoder().decode(body.get("signature").asString())))
                .isTrue();

        // The setup link downloads the file, saved as IdleO-Setup.exe; so does the redirect the website links to.
        var http = HttpClient.newHttpClient();
        var direct = http.send(
                HttpRequest.newBuilder(URI.create(body.get("urls").get("setup").asString()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(direct.statusCode()).isEqualTo(200);
        assertThat(direct.body()).isEqualTo(SETUP);
        assertThat(direct.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"IdleO-Setup.exe\"");
        var location = mvc.perform(get("/v1/agent-releases/latest/setup"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        assertThat(http.send(
                                HttpRequest.newBuilder(URI.create(location)).build(),
                                HttpResponse.BodyHandlers.ofByteArray())
                        .body())
                .isEqualTo(SETUP);
    }

    @Test
    @Order(3)
    void aManifestThatFailsVerificationIsIgnored() throws Exception {
        publish("0.0.6", 100, false);

        assertThat(feed.latest()).get().extracting(ReleaseManifest::version).isEqualTo(VERSION);
        mvc.perform(get("/v1/agent-releases/latest"))
                .andExpect(jsonPath("$.version").value(VERSION));
        // Put the good one back, so the bucket and the cache agree again.
        publish(VERSION, 0, true);
    }

    @Test
    @Order(4)
    void anUpdateIsOfferedOnlyToAHostInTheRolloutThatHasntRolledItBack() throws Exception {
        // 0% rollout: an old agent is told where it stands, and offered nothing.
        var host = connect(OLD_AGENT);
        assertThat(host.stream()
                        .await(OpenStreamResponse::hasHostStatus, WAIT)
                        .getHostStatus()
                        .getState())
                .isEqualTo(HostState.HOST_STATE_PENDING_APPROVAL);
        assertThat(host.stream().receives(OpenStreamResponse::hasUpdateAgent, Duration.ofSeconds(2)))
                .isFalse();

        // The same version re-signed at 100%: offered at once to the connected agent, with the manifest as signed.
        publish(VERSION, 100, true);
        var update =
                host.stream().await(OpenStreamResponse::hasUpdateAgent, WAIT).getUpdateAgent();
        assertThat(update.getVersion()).isEqualTo(VERSION);
        assertThat(update.getManifest().toByteArray())
                .isEqualTo(ReleaseManifestTests.manifest(VERSION, 100).getBytes(StandardCharsets.UTF_8));
        assertThat(update.getManifestSignature().toByteArray())
                .isEqualTo(TestReleaseKeys.get().sign(update.getManifest().toByteArray()));
        assertThat(update.getAppUrl()).contains("idleo-app-" + VERSION + ".zip");
        assertThat(update.getDistroUrl()).contains("idleo-distro-0.1.0.tar.gz");
        // The machine page says an update is waiting for an idle moment.
        mvc.perform(get("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                .andExpect(jsonPath("$.updateWaiting").value(true));

        // A newer agent isn't offered an older release.
        var current = connect("0.1.0");
        assertThat(current.stream().receives(OpenStreamResponse::hasUpdateAgent, Duration.ofSeconds(2)))
                .isFalse();
        current.disconnect();

        // Rolled back: recorded, and never offered to that host again, even on a new connection.
        host.stream()
                .send(TestAgent.request()
                        .setUpdateResult(UpdateResult.newBuilder()
                                .setVersion(VERSION)
                                .setOutcome(UpdateResult.Outcome.OUTCOME_ROLLED_BACK)
                                .setDetail("the trial wasn't confirmed within 5 minutes"))
                        .build());
        host.stream().await(OpenStreamResponse::hasAck, WAIT);
        assertThat(jdbc.queryForObject(
                        "select outcome from hosts.update_results where host_id = ? and version = ?",
                        String.class,
                        host.id(),
                        VERSION))
                .isEqualTo("rolled_back");
        var again = TestHosts.reconnect(host, grpcPort, OLD_AGENT);
        assertThat(again.stream().receives(OpenStreamResponse::hasUpdateAgent, Duration.ofSeconds(2)))
                .isFalse();
        mvc.perform(get("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                .andExpect(jsonPath("$.updateWaiting").value(false));
        again.disconnect();
    }

    private TestHosts.ConnectedHost connect(String agentVersion) throws Exception {
        return TestHosts.connectAs(mvc, grpcPort, agentVersion);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
