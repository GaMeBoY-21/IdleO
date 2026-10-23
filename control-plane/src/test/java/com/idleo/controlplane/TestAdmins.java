package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admins for tests in any package (ADR 0027): a user made an admin as {@code grant-admin} would, with their
 * authenticator set up and an admin session started through the real endpoints. The codes are worked out here,
 * independently of the application's own TOTP code, from RFC 6238.
 */
public final class TestAdmins {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestAdmins() {}

    /** A signed-in admin: their ID token's header, user id, authenticator secret and admin session. */
    public record TestAdmin(String bearer, UUID id, byte[] secret, String session) {

        /** The request, as the console sends it: the ID token and the admin session. */
        public MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
            return request.header("Authorization", bearer).header("Admin-Session", session);
        }
    }

    /** A new user, made an admin, enrolled, with an admin session; their token outlives the tests' clock moves. */
    public static TestAdmin signIn(MockMvc mvc, JdbcTemplate jdbc, Clock clock) throws Exception {
        return signIn(mvc, jdbc, clock, Duration.ofHours(12));
    }

    /** The same, with a token that outlives {@code lifetime}, for tests that move the clock by days. */
    public static TestAdmin signIn(MockMvc mvc, JdbcTemplate jdbc, Clock clock, Duration lifetime) throws Exception {
        var user = TestUsers.signUpLasting(mvc, lifetime);
        grant(jdbc, user.id());
        var enrolment =
                JSON.readTree(mvc.perform(post("/admin/v1/second-factor").header("Authorization", user.bearer()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        var secret = base32(enrolment.get("secret").asString());
        var confirmed = JSON.readTree(mvc.perform(post("/admin/v1/second-factor/confirm")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totp\":\"" + code(secret, clock) + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return new TestAdmin(
                user.bearer(), user.id(), secret, confirmed.get("token").asString());
    }

    /**
     * A new admin session for an enrolled admin, from a code half a minute on (a code is accepted once), as the
     * console's code check does. Returns its token.
     */
    public static String signInAgain(MockMvc mvc, TestAdmin admin, MutableClock clock) throws Exception {
        clock.advance(Duration.ofSeconds(30));
        return JSON.readTree(mvc.perform(post("/admin/v1/session")
                                .header("Authorization", admin.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"totp\":\"" + code(admin.secret(), clock) + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("token")
                .asString();
    }

    /** Makes the user an admin, as {@code grant-admin} does. */
    public static void grant(JdbcTemplate jdbc, UUID userId) {
        jdbc.update("insert into identity.admins (user_id, granted_by, granted_at) values (?, 'tests', now())", userId);
    }

    /** The code an authenticator shows now. */
    public static String code(byte[] secret, Clock clock) {
        return code(secret, Math.floorDiv(clock.instant().getEpochSecond(), 30));
    }

    /** The code for one 30-second time step (RFC 6238, HMAC-SHA1, 6 digits). */
    public static String code(byte[] secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            var hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[19] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 4648 base32, without padding, as the enrolment shows the secret. */
    public static byte[] base32(String text) {
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
