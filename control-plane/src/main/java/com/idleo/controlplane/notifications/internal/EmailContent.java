package com.idleo.controlplane.notifications.internal;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * An email's two forms, plain text and an HTML twin in the app's Night Shift look (ADR 0043): a header band in the
 * canvas colour with the wordmark, a panel with the message and a volt button, and the footer on the page. Email apps
 * can't load web fonts reliably, so it is Arial and Helvetica, in inline styles and tables, with no images, no
 * tracking pixels, no remote styles, and the link written out so it can be read and copied (ADR 0026). It is Day Shift
 * (a pale page, a white panel) and, for an app that asks for the dark scheme, Night Shift (the panel a step lighter than
 * the page), and declares both so an app does not invert it on its own. Its footer says why the person got it and
 * where to change that.
 */
@Component
class EmailContent {

    /** The page with each {@code {{name}}} replaced by its value, escaped, in one pass: a value is never searched again. */
    private static String fill(Map<String, String> values) {
        return PLACEHOLDER.matcher(HTML).replaceAll(found ->
                Matcher.quoteReplacement(HtmlUtils.htmlEscape(values.get(found.group(1)))));
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    /** The line an inbox shows beside the subject, so it is the start of the message and not the start of the layout. */
    private static String preheader(String body) {
        return body.length() <= 110 ? body : body.substring(0, 109) + "…";
    }

    /**
     * The HTML twin. Tables and inline styles, because that is what email apps agree on; the one {@code <style>} is for
     * the apps that follow the device into the dark scheme (their rules are {@code !important} so they beat the inline
     * ones) and for a narrow screen. Colours are the web app's tokens: Day Shift inline, Night Shift in the media query.
     * Placeholders are {@code {{name}}}, filled with escaped text.
     */
    private static final String HTML = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light dark"><meta name="supported-color-schemes" content="light dark">
            <title>{{title}}</title>
            <style>
            :root { color-scheme: light dark; supported-color-schemes: light dark; }
            @media (prefers-color-scheme: dark) {
              .page { background-color: #0C0A1F !important; }
              .panel { background-color: #17142F !important; border-color: #17142F !important; }
              .ink { color: #FFFFFF !important; }
              .ink-2 { color: #B9B5DA !important; }
              .ink-3 { color: #8F8AB8 !important; }
              .rule { border-top-color: #2A2650 !important; }
            }
            @media (max-width: 620px) { .pad { padding: 24px 20px !important; } .gap { padding-left: 20px !important; padding-right: 20px !important; } }
            </style></head>
            <body class="page" style="margin:0;padding:0;background-color:#F4F3FA;" bgcolor="#F4F3FA">
            <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:#F4F3FA;">{{preheader}}</div>
            <table role="presentation" class="page" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#F4F3FA;" bgcolor="#F4F3FA"><tr><td align="center" style="padding:24px 12px;">
            <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;">
            <tr><td bgcolor="#0C0A1F" style="background-color:#0C0A1F;border-radius:28px 28px 0 0;padding:24px 32px;"><span style="font-family:'Arial Black',Arial,Helvetica,sans-serif;font-size:26px;line-height:1;font-weight:900;letter-spacing:-1px;color:#FFFFFF;">idleo</span></td></tr>
            <tr><td class="panel pad" bgcolor="#FFFFFF" style="background-color:#FFFFFF;border:1px solid #E4E2F0;border-top:0;border-radius:0 0 28px 28px;padding:32px;">
            <h1 class="ink" style="margin:0 0 12px 0;font-family:Arial,Helvetica,sans-serif;font-size:24px;line-height:1.25;font-weight:bold;color:#0C0A1F;">{{title}}</h1>
            <p class="ink-2" style="margin:0 0 24px 0;font-family:Arial,Helvetica,sans-serif;font-size:16px;line-height:1.5;color:#4A4668;">{{body}}</p>
            <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr><td bgcolor="#C8FF3D" style="background-color:#C8FF3D;border-radius:14px;"><a href="{{link}}" style="display:inline-block;padding:16px 28px;font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:bold;color:#0C0A1F;text-decoration:none;">Open it</a></td></tr></table>
            <p class="ink-3" style="margin:16px 0 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:1.5;color:#5E5A80;word-break:break-all;">{{link}}</p>
            </td></tr>
            <tr><td class="gap" style="padding:20px 32px 0 32px;"><p class="ink-3 rule" style="margin:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:1.5;color:#5E5A80;">{{why}}<br>
            <a href="{{account}}" class="ink-3" style="color:#5E5A80;">Change what we email you in Account</a></p></td></tr>
            </table></td></tr></table></body></html>
            """;

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
        var html = fill(
                Map.of(
                        "title", message.title(),
                        "preheader", preheader(message.body()),
                        "body", message.body(),
                        "link", link,
                        "why", why,
                        "account", account));
        return new EmailSender.Email(
                to,
                message.title(),
                text,
                html,
                "<" + id + "@" + properties.senderDomain() + ">",
                unsubscribe ? account : null);
    }
}
