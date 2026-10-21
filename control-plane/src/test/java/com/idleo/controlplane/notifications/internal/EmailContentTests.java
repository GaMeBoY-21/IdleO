package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** An email is plain, black and white, and tells the person why they got it (ADR 0026). */
class EmailContentTests {

    private final EmailContent content = new EmailContent(new NotificationProperties(
            URI.create("https://app.example.invalid/"),
            new NotificationProperties.Email("IdleO <notifications@mail.example.invalid>", null)));

    private static final UUID NOTIFICATION = UUID.fromString("0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b");

    private final Message message = new Message(
            "Fine-tune a model finished",
            "It cost ₹0.09. Your outputs are ready to download.",
            "/session/?id=1#outputs");

    @Test
    void theTextCarriesTheMessageTheLinkAndTheFooter() {
        var email = content.of("renter@example.com", NotificationKind.SESSION_COMPLETED, message, NOTIFICATION);

        assertThat(email.to()).isEqualTo("renter@example.com");
        assertThat(email.subject()).isEqualTo("Fine-tune a model finished");
        assertThat(email.text())
                .contains("It cost ₹0.09. Your outputs are ready to download.")
                .contains("Open it: https://app.example.invalid/session/?id=1#outputs")
                .contains("emails about \"Session finished\" are switched on")
                .contains("Change what we email you in Account: https://app.example.invalid/account/");
    }

    @Test
    void aLockedKindSaysItIsAlwaysSent() {
        var email = content.of("renter@example.com", NotificationKind.STOPPED_FOR_CREDIT, message, NOTIFICATION);

        assertThat(email.text()).contains("it's about your money or your safety, so it's always sent");
        assertThat(email.html()).contains("always sent");
    }

    @Test
    void theHtmlHasNoImagesNoRemoteStylesAndNoScripts() {
        var html = content.of("renter@example.com", NotificationKind.SESSION_COMPLETED, message, NOTIFICATION)
                .html();

        assertThat(html).doesNotContain("<img").doesNotContain("<link").doesNotContain("<script");
        assertThat(html).doesNotContain("url(").doesNotContain("@import");
        assertThat(html).contains("<a href=\"https://app.example.invalid/session/?id=1#outputs\"");
    }

    @Test
    void whatsInTheMessageIsEscapedInTheHtml() {
        var odd = new Message("<b>Hi</b>", "A \"quoted\" <script>x</script> & more", "/session/?id=1");

        var html = content.of("a@example.com", NotificationKind.SESSION_FAILED, odd, NOTIFICATION)
                .html();

        assertThat(html).doesNotContain("<script>x</script>").doesNotContain("<b>Hi</b>");
        assertThat(html).contains("&lt;b&gt;Hi&lt;/b&gt;").contains("&amp; more");
    }

    @Test
    void anEmailIsNamedByItsNotificationSoATryAgainIsTheSameEmail() {
        var first = content.of("renter@example.com", NotificationKind.SESSION_COMPLETED, message, NOTIFICATION);
        var again = content.of("renter@example.com", NotificationKind.SESSION_COMPLETED, message, NOTIFICATION);

        assertThat(first.messageId()).isEqualTo("<" + NOTIFICATION + "@mail.example.invalid>");
        assertThat(again.messageId()).isEqualTo(first.messageId());
    }

    @Test
    void onlyTheKindsAPersonCanSwitchOffCarryAnUnsubscribeLink() {
        assertThat(content.of("r@example.com", NotificationKind.SESSION_COMPLETED, message, NOTIFICATION)
                        .unsubscribe())
                .isEqualTo("https://app.example.invalid/account/");
        assertThat(content.of("r@example.com", NotificationKind.STOPPED_FOR_CREDIT, message, NOTIFICATION)
                        .unsubscribe())
                .as("money and safety are always sent")
                .isNull();
    }

    @Test
    void theSmtpMessageKeepsTheIdAndTheHeadersWithATextPart() throws Exception {
        var properties = new NotificationProperties(
                URI.create("https://app.example.invalid/"),
                new NotificationProperties.Email("IdleO <notifications@mail.example.invalid>", null));
        var sender = new SmtpEmailSender(new JavaMailSenderImpl(), properties);

        var optional =
                sender.message(content.of("r@example.com", NotificationKind.SESSION_COMPLETED, message, NOTIFICATION));
        assertThat(optional.getMessageID()).isEqualTo("<" + NOTIFICATION + "@mail.example.invalid>");
        assertThat(optional.getHeader("List-Unsubscribe")).containsExactly("<https://app.example.invalid/account/>");
        var raw = new java.io.ByteArrayOutputStream();
        optional.writeTo(raw);
        assertThat(raw.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Content-Type: text/plain")
                .contains("Content-Type: text/html");

        var locked =
                sender.message(content.of("r@example.com", NotificationKind.STOPPED_FOR_CREDIT, message, NOTIFICATION));
        assertThat(locked.getHeader("List-Unsubscribe")).isNull();
    }
}
