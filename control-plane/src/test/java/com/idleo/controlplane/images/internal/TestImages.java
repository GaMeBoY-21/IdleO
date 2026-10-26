package com.idleo.controlplane.images.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestUsers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A renter's own image made ready for another module's tests (ADR 0042): a verified PAN, the submission, and a whole check
 * reported through the scanner's internal API, on its port with its token. The check found an image needing CUDA 12.4,
 * running as root, working in /app, with a shell.
 */
public final class TestImages {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "tests-only-scanner-token-0123456789abcdef";
    private static final int PORT = 18095;
    private static final AtomicInteger PANS = new AtomicInteger(5000 + (int) (Math.random() * 4000));

    private TestImages() {}

    /** Gives the renter a PAN the fake check verifies. */
    public static void verifyPan(MockMvc mvc, TestUsers.TestUser renter) throws Exception {
        mvc.perform(put("/v1/payout-details/pan")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"ABCPE" + PANS.incrementAndGet() + "F\",\"name\":\"Asha Rao\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verified"));
    }

    /**
     * The renter's image of {@code reference}, checked and ready, with {@code login} ({@code user:token}) for a private one;
     * returns its id. The checks queued before it are done with first, so the scanner's claim is this one.
     */
    public static UUID ready(
            MockMvc mvc,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            TestUsers.TestUser renter,
            String reference,
            @Nullable String login)
            throws Exception {
        jdbc.update("update images.images set state = 'failed', reason = 'not_checked', detail = 'x'"
                + " where state in ('queued', 'checking')");
        var body = new HashMap<String, Object>(Map.of("reference", reference));
        if (login != null) {
            body.put("registryUsername", login.split(":", 2)[0]);
            body.put("registryToken", login.split(":", 2)[1]);
        }
        var created = mvc.perform(post("/v1/images")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var id = UUID.fromString(JSON.readTree(created).get("id").asString());
        var claim = JSON.readTree(internal(mvc, "claim", null, Map.of("scanner", "test")));
        var digest = "sha256:" + UUID.randomUUID().toString().replace("-", "").repeat(2);
        internal(
                mvc,
                "checks/" + id + "/resolved",
                claim,
                Map.of("digest", digest, "platforms", List.of(), "compressedBytes", 3_100_000_000L, "layers", 4));
        var config = new HashMap<String, Object>();
        config.put("cudaRequire", "cuda>=12.4");
        config.put("cudaVersion", "12.4.1");
        config.put("entrypoint", List.of("/opt/entry.sh"));
        config.put("cmd", List.of("python", "train.py"));
        config.put("workingDir", "/app");
        config.put("user", null);
        internal(
                mvc,
                "checks/" + id + "/result",
                claim,
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
                        List.of(),
                        "findings",
                        Map.of(
                                "trivyVersion",
                                "0.75.0",
                                "critical",
                                0,
                                "high",
                                0,
                                "medium",
                                0,
                                "low",
                                0,
                                "vulnerabilities",
                                List.of(),
                                "secrets",
                                List.of())));
        return id;
    }

    private static String internal(MockMvc mvc, String path, @Nullable JsonNode claim, Object body) throws Exception {
        var request = post("/internal/v1/scanner/" + path)
                .with(r -> {
                    r.setLocalPort(PORT);
                    return r;
                })
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (claim != null) {
            request.header("Scanner-Claim", claim.get("claim").asString());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        if (response.getStatus() >= 300) {
            throw new IllegalStateException(path + " answered " + response.getStatus());
        }
        return response.getContentAsString();
    }
}
