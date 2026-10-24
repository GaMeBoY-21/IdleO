package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import java.net.URI;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key rules decide by the route a request reached, never by its spelling (ADR 0025): a percent-encoded letter, a
 * lower-case escape or a trailing slash routes to the same handler as the plain path, so it must meet the same
 * refusal. And they fail closed: a key's request that reaches no annotated handler is refused unless it only reads.
 */
@ControlPlaneTest
class KeyRouteTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping routes;

    private final JsonMapper json = JsonMapper.builder().build();
    private String key;
    private String readKey;
    private UUID readKeyId;
    private String host;
    private String loginId;
    private String loginCode;
    private String linkId;

    @BeforeEach
    void setUp() throws Exception {
        var user = TestUsers.signUp(mvc);
        key = createKey(user, "full").get("key").asString();
        var read = createKey(user, "read");
        readKey = read.get("key").asString();
        readKeyId = UUID.fromString(read.get("id").asString());
        host = json.readTree(mvc.perform(post("/v1/hosts")
                                .header("Authorization", user.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Laptop\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        var login = json.readTree(mvc.perform(post("/v1/cli-logins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"laptop\"}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());
        linkId = json.readTree(mvc.perform(
                                post("/v1/host-links")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"machineName\":\"Laptop\",\"os\":\"windows\",\"agentVersion\":\"0.1.0\",\"gpus\":[]}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        loginId = login.get("id").asString();
        loginCode = login.get("userCode").asString();
    }

    private tools.jackson.databind.JsonNode createKey(TestUsers.TestUser user, String scope) throws Exception {
        return json.readTree(mvc.perform(post("/v1/api-keys")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + scope + "\",\"scope\":\"" + scope + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** Every handler marked browser-only, as "METHOD /pattern". */
    private Set<String> browserOnlyRoutes() {
        var found = new TreeSet<String>();
        routes.getHandlerMethods().forEach((info, handler) -> {
            if (handler.hasMethodAnnotation(BrowserSignInOnly.class)) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    for (var pattern : info.getPathPatternsCondition().getPatternValues()) {
                        found.add(method.name() + " " + pattern);
                    }
                }
            }
        });
        return found;
    }

    @Test
    void theBrowserOnlyCallsAreTheOnesTheAdrNames() {
        assertThat(browserOnlyRoutes())
                .containsExactlyInAnyOrder(
                        "POST /v1/api-keys",
                        "GET /v1/cli-logins",
                        "POST /v1/cli-logins/{id}/approve",
                        "GET /v1/host-links/{id}",
                        "POST /v1/host-links/{id}/approve",
                        "POST /v1/host-links/{id}/deny",
                        "POST /v1/hosts",
                        "DELETE /v1/hosts/{id}",
                        "PUT /v1/hosts/{id}/listing",
                        "POST /v1/hosts/{id}/enrolment-code",
                        "PUT /v1/me/notifications",
                        // The Terms, and the person's own data and account (ADR 0037).
                        "POST /v1/me/notices",
                        "POST /v1/me/exports",
                        "POST /v1/me/deletion",
                        "POST /v1/me/deletion/confirm",
                        "DELETE /v1/me/deletion",
                        // Where a host's payouts go, and giving earnings up (ADR 0040).
                        "PUT /v1/payout-details/pan",
                        "POST /v1/payout-details/upi-checks",
                        "POST /v1/payout-details/upi-checks/{id}/confirm",
                        "POST /v1/payouts/forfeit");
    }

    @Test
    void noSpellingOfABrowserOnlyPathLetsAKeyThrough() throws Exception {
        var tried = 0;
        for (var route : browserOnlyRoutes()) {
            var method = HttpMethod.valueOf(route.substring(0, route.indexOf(' ')));
            var path = route.substring(route.indexOf(' ') + 1)
                    .replace(
                            "{id}",
                            route.contains("cli-logins") ? loginId : route.contains("host-links") ? linkId : host);
            for (var spelling : spellings(path)) {
                var routed = reaches(method, spelling, route);
                var request = request(
                                method,
                                URI.create(spelling + (route.equals("GET /v1/cli-logins") ? "?code=" + loginCode : "")))
                        .header("Authorization", "Bearer " + key)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(route));
                var result = mvc.perform(request).andReturn().getResponse();
                var described = method + " " + spelling + " (routes to the handler: " + routed + ")";
                if (routed || method != HttpMethod.GET) {
                    assertThat(result.getStatus()).as(described).isEqualTo(403);
                    assertThat(result.getContentAsString())
                            .as(described)
                            .contains("/problems/browser-sign-in-required");
                } else {
                    assertThat(result.getStatus()).as(described).isEqualTo(404);
                }
                tried++;
            }
        }
        assertThat(tried).isGreaterThan(100);
    }

    @Test
    void aKeysWriteThatReachesNoHandlerIsRefusedAndAReadIsNotFound() throws Exception {
        mvc.perform(post("/v1/no-such-call").header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));
        mvc.perform(get("/v1/no-such-call").header("Authorization", "Bearer " + key))
                .andExpect(status().isNotFound());
    }

    @Test
    void aReadKeyIsHeldToReadingHoweverThePathIsSpelt() throws Exception {
        mvc.perform(post(URI.create("/v1/session%2Dquotes")).header("Authorization", "Bearer " + readKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/api-key-read-only"));
        var other = UUID.randomUUID();
        mvc.perform(delete(URI.create("/v1/api%2Dkeys/" + other)).header("Authorization", "Bearer " + readKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/api-key-read-only"));
        // Revoking itself is decided by the id the route read, so an escaped id is still its own.
        var escaped = readKeyId.toString().replace("-", "%2d");
        mvc.perform(delete(URI.create("/v1/api%2Dkeys/" + escaped)).header("Authorization", "Bearer " + readKey))
                .andExpect(status().isNoContent());
    }

    /** Whether this spelling routes to the handler of {@code route}, as Spring itself would decide. */
    private boolean reaches(HttpMethod method, String path, String route) throws Exception {
        var probe = new MockHttpServletRequest(method.name(), path);
        ServletRequestPathUtils.parseAndCache(probe);
        var chain = routes.getHandler(probe);
        if (chain == null || !(chain.getHandler() instanceof HandlerMethod handler)) {
            return false;
        }
        return handler.hasMethodAnnotation(BrowserSignInOnly.class);
    }

    /**
     * The path as written, with each letter or hyphen of its fixed part percent-encoded in
     * turn, with all of them encoded in lower-case hex, with all of them in mixed-case hex, and with a trailing slash.
     */
    static List<String> spellings(String path) {
        var out = new ArrayList<String>();
        out.add(path);
        for (var i = 0; i < path.length(); i++) {
            if (encodable(path, i)) {
                out.add(path.substring(0, i) + escape(path.charAt(i), String::toUpperCase) + path.substring(i + 1));
            }
        }
        out.add(encodeAll(path, hex -> hex.toLowerCase(Locale.ROOT)));
        var alternate = new boolean[] {false};
        out.add(encodeAll(path, hex -> {
            alternate[0] = !alternate[0];
            return alternate[0] ? hex.toUpperCase(Locale.ROOT) : hex.toLowerCase(Locale.ROOT);
        }));
        out.add(path + "/");
        return out;
    }

    private static String encodeAll(String path, Function<String, String> casing) {
        var out = new StringBuilder();
        for (var i = 0; i < path.length(); i++) {
            out.append(encodable(path, i) ? escape(path.charAt(i), casing) : String.valueOf(path.charAt(i)));
        }
        return out.toString();
    }

    /** A letter or hyphen of the fixed part: not a slash, and not inside an id (36 characters between slashes). */
    private static boolean encodable(String path, int index) {
        var c = path.charAt(index);
        if (c == '/' || !(Character.isLetter(c) || c == '-')) {
            return false;
        }
        var start = path.lastIndexOf('/', index) + 1;
        var end = path.indexOf('/', index);
        var segment = path.substring(start, end < 0 ? path.length() : end);
        return segment.length() != 36;
    }

    private static String escape(char c, Function<String, String> casing) {
        return "%" + casing.apply(HexFormat.of().toHexDigits((byte) c));
    }

    private static String body(String route) {
        return switch (route) {
            case "POST /v1/api-keys" -> "{\"name\":\"x\",\"scope\":\"full\"}";
            case "POST /v1/hosts" -> "{\"name\":\"x\"}";
            case "PUT /v1/hosts/{id}/listing" -> "{}";
            case "POST /v1/host-links/{id}/approve" -> "{\"name\":\"x\",\"batchPricePaisePerMin\":10}";
            case "PUT /v1/me/notifications" -> "{\"items\":[]}";
            case "PUT /v1/payout-details/pan" -> "{\"pan\":\"ABCPE1234F\",\"name\":\"x\"}";
            case "POST /v1/payout-details/upi-checks" -> "{\"vpa\":\"x@okicici\"}";
            case "POST /v1/payouts/forfeit" -> "{\"statement\":\"I give up my earnings\"}";
            default -> "";
        };
    }
}
