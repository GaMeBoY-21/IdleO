package com.idleo.controlplane.notifications.internal;

import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends email over SMTP with Spring's mail sender, which the {@code spring.mail.*} settings configure: Mailpit
 * locally, the production provider's relay later. Chosen by {@code notifications.email.provider=smtp}, the default;
 * tests set it to {@code recording} and read what would have been sent.
 */
@Component
@ConditionalOnProperty(name = "notifications.email.provider", havingValue = "smtp", matchIfMissing = true)
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final NotificationProperties properties;

    SmtpEmailSender(JavaMailSender mail, NotificationProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    @Override
    public void send(Email email) {
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.email().from());
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            mail.send(message);
        } catch (MessagingException | MailException e) {
            throw new EmailFailedException("The mail server didn't take the email", e);
        }
    }
}
