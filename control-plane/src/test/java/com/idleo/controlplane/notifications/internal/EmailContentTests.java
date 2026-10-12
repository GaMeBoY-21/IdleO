package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** An email is plain, black and white, and tells the person why they got it (ADR 0026). */
class EmailContentTests {

    private final EmailContent content = new EmailContent(new NotificationProperties(
            URI.create("https://app.example.invalid/"), new NotificationProperties.Email("IdleO <a@b.invalid>")));

    private final Message message = new Message(
            "Fine-tune a model finished",
            "It cost ₹0.09. Your outputs are ready to download.",
            "/session/?id=1#outputs");

    @Test
    void theTextCarriesTheMessageTheLinkAndTheFooter() {
        var email = content.of("renter@example.com", NotificationKind.SESSION_COMPLETED, message);

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
        var email = content.of("renter@example.com", NotificationKind.STOPPED_FOR_CREDIT, message);

        assertThat(email.text()).contains("it's about your money or your safety, so it's always sent");
        assertThat(email.html()).contains("always sent");
    }

    @Test
    void theHtmlHasNoImagesNoRemoteStylesAndNoScripts() {
        var html = content.of("renter@example.com", NotificationKind.SESSION_COMPLETED, message)
                .html();

        assertThat(html).doesNotContain("<img").doesNotContain("<link").doesNotContain("<script");
        assertThat(html).doesNotContain("url(").doesNotContain("@import");
        assertThat(html).contains("<a href=\"https://app.example.invalid/session/?id=1#outputs\"");
    }

    @Test
    void whatsInTheMessageIsEscapedInTheHtml() {
        var odd = new Message("<b>Hi</b>", "A \"quoted\" <script>x</script> & more", "/session/?id=1");

        var html = content.of("a@example.com", NotificationKind.SESSION_FAILED, odd)
                .html();

        assertThat(html).doesNotContain("<script>x</script>").doesNotContain("<b>Hi</b>");
        assertThat(html).contains("&lt;b&gt;Hi&lt;/b&gt;").contains("&amp; more");
    }
}
