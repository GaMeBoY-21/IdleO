package com.idleo.controlplane.notifications.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * An email's two forms, plain text and a simple HTML twin in the app's black and white: no images, no tracking
 * pixels, no remote styles, and the link written out so it can be read and copied (ADR 0026). Its footer says why
 * the person got it and where to change that.
 */
@Component
class EmailContent {

    private final NotificationProperties properties;

    EmailContent(NotificationProperties properties) {
        this.properties = properties;
    }

    /** The email for one stored notification: its id names the email ({@code Message-ID}) however often it's tried. */
    EmailSender.Email of(String to, NotificationKind kind, Message message, UUID notificationId) {
        var why = kind.locked()
                ? "You get this because it's about your money or your safety, so it's always sent."
                : "You get this because emails about \"" + kind.title() + "\" are switched on.";
        return email(to, message, notificationId, why, !kind.locked());
    }

    /**
     * An email kept nowhere (ADR 0037): a link that is itself a secret, or the last word to an account that's gone.
     * {@code messageId} names it however often it's tried.
     */
    EmailSender.Email direct(String to, Message message, UUID messageId) {
        return email(
                to,
                message,
                messageId,
                "You get this because of something you asked us to do with your account.",
                false);
    }

    private EmailSender.Email email(String to, Message message, UUID id, String why, boolean unsubscribe) {
        var link = properties.absolute(message.link());
        var account = properties.absolute("/account/");
        var text = message.title() + "\n\n" + message.body() + "\n\nOpen it: " + link + "\n\n--\n" + why
                + "\nChange what we email you in Account: " + account + "\n";
        var html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="margin:0;background:#ffffff;color:#000000;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <div style="max-width:32rem;margin:0 auto;padding:24px 16px;">
                <h1 style="font-size:20px;line-height:1.3;margin:0 0 12px 0;">%s</h1>
                <p style="font-size:16px;line-height:1.5;margin:0 0 20px 0;">%s</p>
                <p style="margin:0 0 28px 0;"><a href="%s" style="color:#000000;font-weight:600;">Open it</a><br>
                <span style="font-size:13px;color:#555555;">%s</span></p>
                <hr style="border:0;border-top:1px solid #d9d9d9;margin:0 0 12px 0;">
                <p style="font-size:13px;line-height:1.5;color:#555555;margin:0;">%s<br>
                <a href="%s" style="color:#555555;">Change what we email you in Account</a></p>
                </div></body></html>
                """.formatted(
                        HtmlUtils.htmlEscape(message.title()),
                        HtmlUtils.htmlEscape(message.body()),
                        HtmlUtils.htmlEscape(link),
                        HtmlUtils.htmlEscape(link),
                        HtmlUtils.htmlEscape(why),
                        HtmlUtils.htmlEscape(account));
        return new EmailSender.Email(
                to,
                message.title(),
                text,
                html,
                "<" + id + "@" + properties.senderDomain() + ">",
                unsubscribe ? account : null);
    }
}
