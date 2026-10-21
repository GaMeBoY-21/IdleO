package com.idleo.controlplane.notifications.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
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

    private final JavaMailSenderImpl mail;
    private final NotificationProperties properties;

    SmtpEmailSender(JavaMailSenderImpl mail, NotificationProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    @Override
    public void send(Email email) {
        try {
            mail.send(message(email));
        } catch (MessagingException | MailException e) {
            throw new EmailFailedException("The mail server didn't take the email", e);
        }
    }

    /**
     * The message as it goes out: plain text with its HTML twin, the notification's own {@code Message-ID} (JavaMail
     * would make a new one on every try), and, for the kinds a person can switch off, {@code List-Unsubscribe} to their
     * email choices (RFC 2369), which mail clients show as an unsubscribe button.
     */
    MimeMessage message(Email email) throws MessagingException {
        var message = new MimeMessage(mail.getSession()) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", email.messageId());
            }
        };
        var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.email().from());
        helper.setTo(email.to());
        helper.setSubject(email.subject());
        helper.setText(email.text(), email.html());
        if (email.unsubscribe() != null) {
            message.setHeader("List-Unsubscribe", "<" + email.unsubscribe() + ">");
        }
        message.saveChanges();
        return message;
    }
}
