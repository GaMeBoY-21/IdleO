package com.idleo.controlplane.images.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.BookingWaits;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.hosts.Blocklists;
import com.idleo.controlplane.hosts.internal.TestBlocklists;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renters' own images (ADR 0042): only a renter with a verified PAN submits one; the scanner reaches the internal API only on
 * its own port with its token, and every step only with its claim; the control plane decides every verdict (the platform,
 * the size and the layers, a cached digest, the blocklist), keeps the warnings in plain words, and a check that crashes, times
 * out or loses its scanner is not checked, never a pass. Booking needs a ready image; a newer blocklist blocks a cached one
 * and cancels its sessions that haven't started; a private image's token is sealed and opened only to pull.
 */
@ControlPlaneTest
class ImageCheckTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "tests-only-scanner-token-0123456789abcdef";
    private static final int PORT = 18095;
    private static final AtomicInteger PANS = new AtomicInteger(1000 + (int) (Math.random() * 4000));

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    /** The waits on what matching decides (BookingWaits). */
    private BookingWaits waits() {
        return new BookingWaits(mvc, context);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ScanStore scans;

    @Autowired
    ImageJudge judge;

    @Autowired
    PlatformTransactionManager transactions;

    @org.junit.jupiter.api.AfterEach
    void resetClock() {
        clock.reset();
    }

    @BeforeEach
    void aBlocklistIsCached() {
        TestBlocklists.ensureOne(context);
        // Each test's checks are the only ones queued: earlier tests' leftovers are done with.
        jdbc.update("update images.images set state = 'failed', reason = 'not_checked', detail = 'x'"
                + " where state in ('queued', 'checking')");
    }

    /** A renter whose PAN the fake check verifies. */
    private TestUsers.TestUser verifiedRenter() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        mvc.perform(put("/v1/payout-details/pan")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"ABCPE" + PANS.incrementAndGet() + "F\",\"name\":\"Asha Rao\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verified"));
        return renter;
    }

    private ResultActions submit(TestUsers.TestUser renter, String body) throws Exception {
        return mvc.perform(post("/v1/images")
                .header("Authorization", renter.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID submitted(TestUsers.TestUser renter, String reference) throws Exception {
        var body = submit(renter, "{\"reference\":\"" + reference + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("queued"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    private JsonNode image(TestUsers.TestUser renter, UUID id) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/images/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** A request on the scanner's own port, with its token. */
    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request, String token) {
        return request.with(r -> {
                    r.setLocalPort(PORT);
                    return r;
                })
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }

    /** The scanner, as the control plane sees it. */
    private final class Scanner {

        JsonNode claim() throws Exception {
            var response = mvc.perform(
                            internal(post("/internal/v1/scanner/claim"), TOKEN).content("{\"scanner\":\"test\"}"))
                    .andReturn()
                    .getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            return JSON.readTree(response.getContentAsString());
        }

        ResultActions step(JsonNode claim, String step, Object body) throws Exception {
            return mvc.perform(internal(
                            post("/internal/v1/scanner/checks/"
                                    + claim.get("checkId").asString() + "/" + step),
                            TOKEN)
                    .header("Scanner-Claim", claim.get("claim").asString())
                    .content(JSON.writeValueAsString(body)));
        }

        boolean resolved(JsonNode claim, String digest, long compressed, int layers) throws Exception {
            var answer = step(
                            claim,
                            "resolved",
                            Map.of(
                                    "digest",
                                    digest,
                                    "platforms",
                                    List.of(),
                                    "compressedBytes",
                                    compressed,
                                    "layers",
                                    layers))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            return JSON.readTree(answer).get("proceed").asBoolean();
        }

        void result(
                JsonNode claim,
                String digest,
                List<Map<String, Object>> executables,
                String user,
                List<Map<String, Object>> secrets)
                throws Exception {
            var config = new java.util.HashMap<String, Object>();
            config.put("cudaRequire", "cuda>=12.4 brand=tesla,driver>=470");
            config.put("cudaVersion", "12.4.1");
            config.put("entrypoint", List.of("/opt/entry.sh"));
            config.put("cmd", List.of("python", "train.py"));
            config.put("workingDir", "/app");
            config.put("user", user);
            step(
                            claim,
                            "result",
                            Map.of(
                                    "digest",
                                    digest,
                                    "compressedBytes",
                                    3_100_000_000L,
                                    "layers",
                                    4,
                                    "config",
                                    config,
                                    "unpackedBytes",
                                    8_400_000_000L,
                                    "hasShell",
                                    true,
                                    "executables",
                                    executables,
                                    "findings",
                                    Map.of(
                                            "trivyVersion", "0.75.0",
                                            "critical", 2,
                                            "high", 7,
                                            "medium", 30,
                                            "low", 5,
                                            "vulnerabilities",
                                                    List.of(Map.of(
                                                            "severity", "CRITICAL",
                                                            "id", "CVE-2024-0001",
                                                            "packageName", "openssl",
                                                            "installed", "3.0.2",
                                                            "fixed", "3.0.13",
                                                            "title", "A flaw")),
                                            "secrets", secrets)))
                    .andExpect(status().isNoContent());
        }

        /** A whole clean check of {@code digest}. */
        void checks(String digest, List<String> hashes) throws Exception {
            var claim = claim();
            assertThat(resolved(claim, digest, 3_100_000_000L, 4)).isTrue();
            result(
                    claim,
                    digest,
                    hashes.stream()
                            .map(h -> Map.<String, Object>of("path", "usr/bin/tool", "sha256", h, "layer", 0))
                            .toList(),
                    "10001",
                    List.of());
        }
    }

    private static String digest() {
        return "sha256:" + UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private static String sha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    @Test
    void onlyARenterWithAVerifiedPanMaySubmitAndIsToldHow() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        submit(renter, "{\"reference\":\"pytorch/pytorch:2.5.1\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/verified-pan-required"));
        mvc.perform(get("/v1/images").header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canUse").value(false))
                .andExpect(jsonPath("$.whyNot").value(org.hamcrest.Matchers.containsString("Payout details")));
    }

    @Test
    void aReferenceToAnAddressOrNoPublicRegistryIsRefused() throws Exception {
        var renter = verifiedRenter();
        for (var bad : List.of(
                "169.254.169.254/latest/meta",
                "localhost:5432/x",
                "postgres/x:1".replace("postgres/", "db.internal/"),
                "Ubuntu",
                "a b")) {
            submit(renter, "{\"reference\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        }
        submit(renter, "{\"reference\":\"ghcr.io/me/job:v1\",\"registryUsername\":\"me\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void theInternalApiIsAnsweredOnlyOnItsPortWithItsToken() throws Exception {
        mvc.perform(post("/internal/v1/scanner/claim")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scanner\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(internal(post("/internal/v1/scanner/claim"), "not-the-token-but-just-as-long-0000000")
                        .content("{\"scanner\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/unauthenticated"));
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        mvc.perform(get("/v1/me")
                        .with(r -> {
                            r.setLocalPort(PORT);
                            return r;
                        })
                        .header("Authorization", renter.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/v1/session").with(r -> {
                    r.setLocalPort(PORT);
                    return r;
                }))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCleanImageIsReadyWithItsFindingsAndWarningsInPlainWords() throws Exception {
        var renter = verifiedRenter();
        var id = submitted(renter, "pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime");
        var scanner = new Scanner();
        var claim = scanner.claim();
        assertThat(claim.get("reference").asString())
                .isEqualTo("docker.io/pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime");
        assertThat(claim.get("login").isNull()).isTrue();
        assertThat(claim.get("platform").asString()).isEqualTo("linux/amd64");
        var digest = digest();
        assertThat(scanner.resolved(claim, digest, 3_100_000_000L, 4)).isTrue();
        scanner.step(
                        claim,
                        "progress",
                        Map.of("phase", "downloading", "doneBytes", 1_000_000_000L, "totalBytes", 3_100_000_000L))
                .andExpect(jsonPath("$.proceed").value(true));
        assertThat(image(renter, id).at("/progress/doneBytes").asLong()).isEqualTo(1_000_000_000L);
        scanner.result(
                claim,
                digest,
                List.of(Map.of("path", "usr/bin/python3", "sha256", sha(), "layer", 1)),
                "",
                List.of(
                        Map.of(
                                "path",
                                "/app/.env",
                                "rule",
                                "aws-access-key-id",
                                "category",
                                "AWS",
                                "title",
                                "AWS Access Key ID"),
                        Map.of(
                                "path",
                                "/app/.env",
                                "rule",
                                "aws-secret-access-key",
                                "category",
                                "AWS",
                                "title",
                                "AWS Secret Access Key")));

        var ready = image(renter, id);
        assertThat(ready.get("state").asString()).isEqualTo("ready");
        assertThat(ready.at("/result/pinnedReference").asString()).isEqualTo("docker.io/pytorch/pytorch@" + digest);
        assertThat(ready.at("/result/cudaNeeded").asString()).isEqualTo("12.4");
        assertThat(ready.at("/result/workingDir").asString()).isEqualTo("/app");
        assertThat(ready.at("/result/vulnerabilities/critical").asInt()).isEqualTo(2);
        var warnings = ready.at("/result/warnings")
                .valueStream()
                .map(w -> w.get("message").asString())
                .toList();
        assertThat(warnings)
                .contains(
                        "Your image seems to contain an AWS key in /app/.env. Anyone with this image can read it.",
                        "Trivy found 2 critical and 7 high known vulnerabilities in your image's packages.");
        assertThat(warnings).hasSize(3).anyMatch(w -> w.startsWith("Your image runs as root."));
        mvc.perform(get("/v1/images/" + id + "/vulnerabilities").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.vulnerabilities[0].id").value("CVE-2024-0001"))
                .andExpect(jsonPath("$.counts.high").value(7));

        // Another renter's image is not theirs to see.
        var other = verifiedRenter();
        mvc.perform(get("/v1/images/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSecondSubmissionOfTheSameDigestIsAnsweredFromTheCacheAndANewPushIsANewCheck() throws Exception {
        var scanner = new Scanner();
        var first = verifiedRenter();
        var digest = digest();
        submitted(first, "ghcr.io/me/job:v1");
        scanner.checks(digest, List.of(sha()));

        var second = verifiedRenter();
        var again = submitted(second, "ghcr.io/me/job:v1");
        var claim = scanner.claim();
        assertThat(scanner.resolved(claim, digest, 3_100_000_000L, 4))
                .as("nothing to download: cached")
                .isFalse();
        assertThat(image(second, again).get("state").asString()).isEqualTo("ready");

        // The tag moved: the same reference resolves to another digest, which is checked as anything new is.
        var pushed = submitted(second, "ghcr.io/me/job:v1");
        claim = scanner.claim();
        assertThat(scanner.resolved(claim, digest(), 3_100_000_000L, 4)).isTrue();
        assertThat(image(second, pushed).get("state").asString()).isEqualTo("checking");
        assertThat(image(second, again).at("/result/digest").asString())
                .as("what was checked stays pinned")
                .isEqualTo(digest);
    }

    @Test
    void theWrongPlatformTooLargeOrTooManyLayersIsBlockedBeforeAnythingIsDownloaded() throws Exception {
        var renter = verifiedRenter();
        var scanner = new Scanner();
        var arm = submitted(renter, "ghcr.io/me/arm:v1");
        var claim = scanner.claim();
        var answer = scanner.step(
                        claim,
                        "resolved",
                        Map.of("platforms", List.of("linux/arm64/v8"), "compressedBytes", 0, "layers", 0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JSON.readTree(answer).get("proceed").asBoolean()).isFalse();
        assertThat(image(renter, arm).get("reason").asString()).isEqualTo("wrong_platform");
        assertThat(image(renter, arm).get("detail").asString())
                .contains("linux/arm64/v8 only")
                .contains("--platform linux/amd64");

        var big = submitted(renter, "ghcr.io/me/big:v1");
        assertThat(scanner.resolved(scanner.claim(), digest(), 15_000_000_001L, 10))
                .isFalse();
        assertThat(image(renter, big).get("detail").asString())
                .contains("15.0 GB compressed; the most we take is 15.0 GB");

        var layered = submitted(renter, "ghcr.io/me/layers:v1");
        assertThat(scanner.resolved(scanner.claim(), digest(), 1_000_000L, 128)).isFalse();
        assertThat(image(renter, layered).get("reason").asString()).isEqualTo("too_many_layers");
    }

    @Test
    void aRefusalBlocksAndACrashOrALostScannerIsNotCheckedNeverAPass() throws Exception {
        var renter = verifiedRenter();
        var scanner = new Scanner();
        var bomb = submitted(renter, "ghcr.io/me/bomb:v1");
        var claim = scanner.claim();
        scanner.resolved(claim, digest(), 1_000_000L, 1);
        scanner.step(
                        claim,
                        "refused",
                        Map.of("reason", "too_large_unpacked", "detail", "Layer 1 is too large once unpacked."))
                .andExpect(status().isNoContent());
        assertThat(image(renter, bomb).get("state").asString()).isEqualTo("blocked");

        var crashed = submitted(renter, "ghcr.io/me/crash:v1");
        claim = scanner.claim();
        scanner.step(claim, "failed", Map.of("detail", "Trivy failed (exit status: 1): FATAL"))
                .andExpect(status().isNoContent());
        var failed = image(renter, crashed);
        assertThat(failed.get("state").asString()).isEqualTo("failed");
        assertThat(failed.get("reason").asString()).isEqualTo("not_checked");
        assertThat(failed.get("detail").asString())
                .contains("Nothing was decided")
                .contains("can't be booked");

        // A scanner that claims and goes quiet loses the claim; after the tries the check is failed, not checked.
        var quiet = submitted(renter, "ghcr.io/me/quiet:v1");
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(scanner.claim().get("checkId").asString()).isEqualTo(quiet.toString());
            clock.advance(Duration.ofSeconds(3));
        }
        awaitState(renter, quiet, "failed");
        assertThat(image(renter, quiet).get("detail").asString()).contains("stopped reporting 3 times");
    }

    private void awaitState(TestUsers.TestUser renter, UUID id, String state) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!image(renter, id).get("state").asString().equals(state)) {
            assertThat(System.nanoTime()).as("image %s to be %s", id, state).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    @Test
    void aStepWithAnotherClaimIsRefused() throws Exception {
        var renter = verifiedRenter();
        var scanner = new Scanner();
        submitted(renter, "ghcr.io/me/claims:v1");
        var claim = scanner.claim();
        mvc.perform(internal(
                                post("/internal/v1/scanner/checks/"
                                        + claim.get("checkId").asString() + "/failed"),
                                TOKEN)
                        .header("Scanner-Claim", UUID.randomUUID().toString())
                        .content("{\"detail\":\"not mine\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void bookingNeedsTheRenterReadyImageAndPinsItsDigest() throws Exception {
        var renter = verifiedRenter();
        TestCredit.grant(context, renter.id(), 100_000);
        var scanner = new Scanner();
        var id = submitted(renter, "ghcr.io/me/book:v1");
        book(renter, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/image-not-ready"));
        var digest = digest();
        scanner.checks(digest, List.of(sha()));
        var session = JSON.readTree(book(renter, id)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(session.get("image").asString()).isEqualTo("ghcr.io/me/book@" + digest);
        assertThat(session.get("imageId").asString()).isEqualTo(id.toString());

        // Another renter can't book it; and a template with its own image takes none.
        var other = verifiedRenter();
        TestCredit.grant(context, other.id(), 100_000);
        book(other, id).andExpect(status().isNotFound());
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"templateId\":\"charged-run\",\"maxSpendPaise\":1000,\"maxPricePaisePerMin\":5,\"imageId\":\""
                                        + id + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions book(TestUsers.TestUser renter, UUID imageId) throws Exception {
        return mvc.perform(post("/v1/sessions")
                .header("Authorization", renter.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":\"custom-image\",\"maxSpendPaise\":1000,\"maxPricePaisePerMin\":5,"
                        + "\"params\":{\"command\":\"python train.py\"},\"imageId\":\"" + imageId + "\"}"));
    }

    @Test
    void aNewerBlocklistBlocksACachedImageAndCancelsItsSessionsNotStarted() throws Exception {
        var renter = verifiedRenter();
        TestCredit.grant(context, renter.id(), 100_000);
        var scanner = new Scanner();
        var id = submitted(renter, "ghcr.io/me/later:v1");
        var digest = digest();
        var program = sha();
        scanner.checks(digest, List.of(sha(), program));
        var session = JSON.readTree(book(renter, id)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();

        // A list that names the program arrives: the cached image is judged again from the hashes its check kept.
        var newer = new Blocklists.Programs("2099-01-01T00:00:00Z", Set.of(program));
        Blocklists listing = new Blocklists() {
            @Override
            public Optional<Published> newerThan(String held) {
                return Optional.empty();
            }

            @Override
            public Set<String> learnedBuilds() {
                return Set.of();
            }

            @Override
            public Optional<Programs> programs() {
                return Optional.of(newer);
            }
        };
        var rechecks = new BlocklistRechecks(listing, scans, judge, transactions, clock);
        assertThat(rechecks.recheck()).isPositive();
        var blocked = image(renter, id);
        assertThat(blocked.get("state").asString()).isEqualTo("blocked");
        assertThat(blocked.get("reason").asString()).isEqualTo("blocklisted");
        book(renter, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/image-blocked"));
        // A session that was still waiting is cancelled (sessions' ImageBlockedSessionsTests holds that alone); one
        // that
        // had already ended for want of a host is left as it was.
        var ended = waits().until(
                        () -> {
                            var state = jdbc.queryForObject(
                                    "select state from sessions.sessions where id = ?::uuid", String.class, session);
                            return List.of("REQUESTED", "MATCHED", "PROVISIONING")
                                            .contains(state)
                                    ? null
                                    : state;
                        },
                        () -> "session " + session + ", not started, stayed waiting");
        assertThat(ended).as("it never ran").isIn("CANCELLED", "NO_HOST_FREE");
        assertThat(scans.find(digest).orElseThrow().blockedPath()).isEqualTo("/usr/bin/tool (layer 1)");
        // Its owner is told, once.
        var told = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (jdbc.queryForObject(
                        "select count(*) from notifications.notifications where user_id = ? and kind = 'image-blocked'",
                        Integer.class,
                        renter.id())
                != 1) {
            assertThat(System.nanoTime()).as("the owner is told").isLessThan(told);
            Thread.sleep(100);
        }
    }

    @Test
    void aPrivateImagesTokenIsSealedOpenedOnlyToPullAndRemovedWithTheImage() throws Exception {
        var renter = verifiedRenter();
        var token = "ghp_readonly_" + UUID.randomUUID();
        var body = submit(
                        renter,
                        "{\"reference\":\"ghcr.io/me/private:v1\",\"registryUsername\":\"me\",\"registryToken\":\""
                                + token + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.privateRegistry").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(token);
        var id = UUID.fromString(JSON.readTree(body).get("id").asString());
        var sealed = jdbc.queryForObject(
                "select sealed_token from images.registry_logins where user_id = ?", byte[].class, renter.id());
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain(token);
        // Nowhere in the database in clear: not in any text column of the images schema, nor in the idempotency record.
        assertThat(jdbc.queryForObject(
                        "select count(*) from images.images i where i.reference like ? or i.detail like ?",
                        Integer.class,
                        "%" + token + "%",
                        "%" + token + "%"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from images.idempotency_keys where response_body like ?",
                        Integer.class,
                        "%" + token + "%"))
                .isZero();

        var claim = new Scanner().claim();
        assertThat(claim.at("/login/username").asString()).isEqualTo("me");
        assertThat(claim.at("/login/token").asString()).isEqualTo(token);
        mvc.perform(get("/v1/images/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain(token));

        mvc.perform(delete("/v1/images/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                        "select count(*) from images.registry_logins where user_id = ?", Integer.class, renter.id()))
                .isZero();
    }
}
