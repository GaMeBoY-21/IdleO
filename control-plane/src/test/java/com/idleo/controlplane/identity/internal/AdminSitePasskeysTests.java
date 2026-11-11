package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestPasskey;
import com.idleo.controlplane.WebOrigins;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin console moved to its own site, admin.idleo.in (ADR 0058), and its passkeys (ADR 0051) are made for the
 * relying party {@code idleo.in} as before. WebAuthn lets a page use a passkey made for its own domain or one above it,
 * so they work there as they are: proved here with production's names, through Spring Security's real checks, and
 * refused from any other site's page.
 */
@ControlPlaneTest
class AdminSitePasskeysTests {

    static final String ADMIN_SITE = "https://admin.idleo.in";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    IdGenerator ids;

    final JsonMapper json = JsonMapper.builder().build();

    /** Production's passkeys: relying party idleo.in, used on the admin site alone. */
    AdminPasskeys production() {
        var environment = new MockEnvironment().withProperty("web.admin-origins", ADMIN_SITE);
        return new AdminPasskeys(
                JdbcClient.create(jdbc),
                ids,
                clock,
                new AdminProperties(
                        "unused",
                        Duration.ofMinutes(30),
                        Duration.ofHours(8),
                        "IdleO",
                        Duration.ofMinutes(5),
                        new AdminProperties.Passkeys("idleo.in", "IdleO")),
                environment);
    }

    @Test
    void aPasskeyMadeForIdleoInRegistersAndSignsInOnTheAdminSite() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var passkeys = production();
        var phone = new TestPasskey();
        phone.origin = ADMIN_SITE;
        phone.rpId = "idleo.in";

        assertThat(register(passkeys, admin.id(), phone)).isTrue();
        assertThat(signIn(passkeys, admin.id(), phone)).isTrue();
        assertThat(WebOrigins.coversHost("idleo.in", ADMIN_SITE)).isTrue();
    }

    @Test
    void theSamePasskeyIsRefusedOnAnyOtherSitesPage() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var passkeys = production();
        var phone = new TestPasskey();
        phone.origin = ADMIN_SITE;
        phone.rpId = "idleo.in";
        assertThat(register(passkeys, admin.id(), phone)).isTrue();

        // The renter app and the host site are under idleo.in too, but the console isn't theirs (ADR 0058).
        for (var elsewhere : List.of("https://app.idleo.in", "https://host.idleo.in", "https://idleo.in")) {
            phone.origin = elsewhere;
            assertThat(signIn(passkeys, admin.id(), phone)).as(elsewhere).isFalse();
        }
        phone.origin = ADMIN_SITE;
        assertThat(signIn(passkeys, admin.id(), phone)).isTrue();
    }

    @Test
    void aPasskeyMadeForAnotherDomainIsRefused() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var stranger = new TestPasskey();
        stranger.origin = ADMIN_SITE;
        stranger.rpId = "admin.example.com";
        assertThat(register(production(), admin.id(), stranger)).isFalse();
    }

    private boolean register(AdminPasskeys passkeys, UUID userId, TestPasskey passkey) throws Exception {
        var registering = passkeys.startRegistration(userId);
        var options = options(
                registering.challengeId(), registering.options().getChallenge().toBase64UrlString());
        var answer = json.readTree(passkey.register(options, "Phone"));
        return passkeys.finishRegistration(
                        userId,
                        registering.challengeId(),
                        "Phone",
                        answer.get("credentialId").asString(),
                        answer.get("clientDataJson").asString(),
                        answer.get("attestationObject").asString(),
                        List.of("internal"))
                .isPresent();
    }

    private boolean signIn(AdminPasskeys passkeys, UUID userId, TestPasskey passkey) throws Exception {
        var asserting = passkeys.startAssertion(userId).orElseThrow();
        var answer = passkey.assertion(options(
                asserting.challengeId(), asserting.options().getChallenge().toBase64UrlString()));
        return passkeys.check(
                userId,
                new AdminPasskeys.Assertion(
                        asserting.challengeId(),
                        answer.get("credentialId"),
                        answer.get("clientDataJson"),
                        answer.get("authenticatorData"),
                        answer.get("signature"),
                        null));
    }

    private JsonNode options(UUID challengeId, String challenge) {
        return json.valueToTree(Map.of("challengeId", challengeId.toString(), "challenge", challenge));
    }
}
