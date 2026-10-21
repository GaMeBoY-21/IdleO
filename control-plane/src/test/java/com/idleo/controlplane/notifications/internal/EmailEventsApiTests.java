package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The email provider's bounces and complaints (ADR 0036), with Resend's own sample payloads (src/test/resources/resend,
 * copied from its documentation) signed as Resend signs them, and the signature checked against Svix's own published
 * example.
 */
@ControlPlaneTest
class EmailEventsApiTests {

    /** The sample's recipient. */
    private static final String SAMPLE_ADDRESS = "delivered@resend.dev";

    @Autowired
    MockMvc mvc;

    @Autowired
    EmailSuppressions suppressions;

    @Autowired
    MeterRegistry meters;

    @Value("${notifications.email.webhook-secret}")
    String secret;

    private static byte[] sample(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/resend/" + name + ".json"));
    }

    private static String sign(String secret, String id, long timestamp, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
        mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private org.springframework.test.web.servlet.ResultActions deliver(
            String id, long timestamp, String signature, byte[] body) throws Exception {
        return mvc.perform(post("/v1/email-events/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", id)
                .header("svix-timestamp", String.valueOf(timestamp))
                .header("svix-signature", signature)
                .content(body));
    }

    private double emails(String outcome) {
        var counter =
                meters.find("notifications.emails").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theSignatureIsCheckedAsSvixPublishesIt() {
        // docs.svix.com/receiving/verifying-payloads/how-manual: its example secret, message, time and signature.
        var properties = new NotificationProperties(
                URI.create("https://app.example.invalid"),
                new NotificationProperties.Email("n@example.invalid", "whsec_plJ3nmyCDGBKInavdOK15jsl"));
        var at = Clock.fixed(Instant.ofEpochSecond(1731705121), ZoneOffset.UTC);
        var body = "{\"event_type\":\"ping\",\"data\":{\"success\":true}}".getBytes(StandardCharsets.UTF_8);
        var headers = Map.of(
                "svix-id", "msg_loFOjxBNrRLzqYUf",
                "svix-timestamp", "1731705121",
                "svix-signature", "v1,rAvfW3dJ/X/qxhsaXPOyyCGmRKsaKWcsNccKXlIktD0=");

        assertThat(new ResendEmailEvents(properties, at).read(headers, body).kind())
                .isEqualTo(EmailEvents.Kind.OTHER);
        // Among others, in any order, one good signature is enough.
        var several = Map.of(
                "svix-id", "msg_loFOjxBNrRLzqYUf",
                "svix-timestamp", "1731705121",
                "svix-signature", "v1,bm90IGl0 v1,rAvfW3dJ/X/qxhsaXPOyyCGmRKsaKWcsNccKXlIktD0=");
        assertThat(new ResendEmailEvents(properties, at).read(several, body).id())
                .isEqualTo("msg_loFOjxBNrRLzqYUf");

        var later = Clock.fixed(Instant.ofEpochSecond(1731705121 + 301), ZoneOffset.UTC);
        assertThatThrownBy(() -> new ResendEmailEvents(properties, later).read(headers, body))
                .as("too old to trust: a replay")
                .isInstanceOf(EmailEvents.Unsigned.class);
        var changed = "{\"event_type\":\"ping\",\"data\":{\"success\":false}}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ResendEmailEvents(properties, at).read(headers, changed))
                .isInstanceOf(EmailEvents.Unsigned.class);
    }

    @Test
    void anUnsignedOrBadlySignedCallIsRefusedAndChangesNothing() throws Exception {
        var body = sample("email.bounced");
        var now = Instant.now().getEpochSecond();

        mvc.perform(post("/v1/email-events/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/unauthenticated"));
        deliver("msg_" + UUID.randomUUID(), now, sign("whsec_" + "d3Jvbmcta2V5LWZvci10ZXN0cw==", "x", now, body), body)
                .andExpect(status().isUnauthorized());

        assertThat(suppressions.reasonFor("someone-else@example.com")).isEmpty();
    }

    @Test
    void aHardBounceStopsEveryEmailToTheAddressAndTheAppSaysSo() throws Exception {
        var person = TestUsers.signUpWithEmail(mvc, SAMPLE_ADDRESS);
        mvc.perform(get("/v1/me").header("Authorization", person.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailUndeliverable").value(false));
        var bouncedBefore = emails("bounced");

        var body = sample("email.bounced");
        var now = Instant.now().getEpochSecond();
        var id = "msg_" + UUID.randomUUID();
        deliver(id, now, sign(secret, id, now, body), body).andExpect(status().isNoContent());
        // The provider delivering the same event again changes nothing.
        deliver(id, now, sign(secret, id, now, body), body).andExpect(status().isNoContent());

        assertThat(suppressions.reasonFor(SAMPLE_ADDRESS)).contains(EmailSuppressions.Reason.BOUNCED);
        assertThat(suppressions.blocks(SAMPLE_ADDRESS, NotificationKind.SESSION_COMPLETED))
                .isTrue();
        assertThat(suppressions.blocks(SAMPLE_ADDRESS, NotificationKind.STOPPED_FOR_CREDIT))
                .as("it can't be delivered, money or not")
                .isTrue();
        assertThat(emails("bounced") - bouncedBefore).isEqualTo(2);
        mvc.perform(get("/v1/me").header("Authorization", person.bearer()))
                .andExpect(jsonPath("$.emailUndeliverable").value(true));
        // Another address, the person's next one, is fine.
        assertThat(suppressions.undeliverable("someone-else@example.com")).isFalse();
    }

    @Test
    void aComplaintStopsWhatCanBeSwitchedOffButNotMoneyOrSafety() throws Exception {
        var address = "complainer-" + UUID.randomUUID() + "@example.com";
        suppressions.record(address, EmailSuppressions.Reason.COMPLAINED, "msg_test");

        assertThat(suppressions.blocks(address, NotificationKind.SESSION_COMPLETED))
                .isTrue();
        assertThat(suppressions.blocks(address, NotificationKind.STOPPED_FOR_CREDIT))
                .isFalse();
        assertThat(suppressions.undeliverable(address)).isFalse();

        // A bounce later outranks the complaint; a complaint after a bounce doesn't undo it.
        suppressions.record(address, EmailSuppressions.Reason.BOUNCED, "msg_test_2");
        suppressions.record(address, EmailSuppressions.Reason.COMPLAINED, "msg_test_3");
        assertThat(suppressions.undeliverable(address)).isTrue();
    }

    @Test
    void theSamplesAreReadAsWhatTheyAre() throws Exception {
        var events = new ResendEmailEvents(
                new NotificationProperties(
                        URI.create("https://app.example.invalid"),
                        new NotificationProperties.Email("n@x.invalid", secret)),
                Clock.systemUTC());
        var now = Instant.now().getEpochSecond();
        for (var expected : Map.of(
                        "email.bounced", EmailEvents.Kind.HARD_BOUNCE,
                        "email.complained", EmailEvents.Kind.COMPLAINT,
                        "email.delivered", EmailEvents.Kind.OTHER)
                .entrySet()) {
            var body = sample(expected.getKey());
            var read = events.read(
                    Map.of(
                            "svix-id", "msg_1",
                            "svix-timestamp", String.valueOf(now),
                            "svix-signature", sign(secret, "msg_1", now, body)),
                    body);
            assertThat(read.kind()).as(expected.getKey()).isEqualTo(expected.getValue());
            assertThat(read.addresses()).containsExactly(SAMPLE_ADDRESS);
        }
        // A bounce the receiving server may take later is no reason to stop.
        var temporary = JsonMapper.builder().build().readTree(sample("email.bounced"));
        ((tools.jackson.databind.node.ObjectNode) temporary.path("data").path("bounce")).put("type", "Temporary");
        var body = temporary.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(events.read(
                                Map.of(
                                        "svix-id", "msg_2",
                                        "svix-timestamp", String.valueOf(now),
                                        "svix-signature", sign(secret, "msg_2", now, body)),
                                body)
                        .kind())
                .isEqualTo(EmailEvents.Kind.SOFT_BOUNCE);
    }
}
