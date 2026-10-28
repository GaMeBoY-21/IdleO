package com.idleo.controlplane.notifications.internal;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes one sample of every email as a file, for the product owner to look at (web/e2e/screenshots.ts lays them out in
 * the contact sheet): {@code ./mvnw test -Dtest=EmailSamples -Didleo.emails.out=<folder>}. It does nothing without the
 * property, so the build never writes files. The words are the ones {@link Messages} writes, with invented figures.
 */
@EnabledIfSystemProperty(named = "idleo.emails.out", matches = ".+")
class EmailSamples {

    private static final UUID ID = UUID.fromString("0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b");

    private final EmailContent content = new EmailContent(new NotificationProperties(
            URI.create("https://app.idleo.in/"),
            new NotificationProperties.Email("IdleO <notifications@mail.idleo.in>", null)));

    @Test
    void writesEveryKind() throws Exception {
        var folder = Path.of(System.getProperty("idleo.emails.out"));
        Files.createDirectories(folder);
        var samples = new LinkedHashMap<NotificationKind, Message>();
        samples.put(
                NotificationKind.SESSION_COMPLETED,
                new Message(
                        "Fine-tune a model finished",
                        "It cost ₹4.92. Your outputs are ready to download.",
                        "/session/?id=" + ID + "#outputs"));
        samples.put(
                NotificationKind.SESSION_FAILED,
                new Message(
                        "Run your code didn't finish",
                        "Your code stopped with an error. It cost ₹0.24. Open the log to see why, then try again.",
                        "/session/?id=" + ID));
        samples.put(
                NotificationKind.STOPPED_FOR_CREDIT,
                new Message(
                        "Stopped: your credit ran out",
                        "Fine-tune a model: your credit ran out, so it saved a checkpoint and stopped. It cost ₹9.60."
                                + " Add credit to carry on from the checkpoint.",
                        "/session/?id=" + ID));
        samples.put(
                NotificationKind.LOW_CREDIT,
                new Message(
                        "Your credit is running low",
                        "Fine-tune a model has under 10 minutes of credit left. When it runs out it saves a checkpoint"
                                + " and stops, and you can carry on from there.",
                        "/session/?id=" + ID));
        samples.put(
                NotificationKind.NO_HOST_FREE,
                new Message(
                        "No GPU was free",
                        "No GPU with enough memory was free just now. You weren't charged. Try again in a few minutes.",
                        "/session/?id=" + ID));
        samples.put(
                NotificationKind.HOST_LOST,
                new Message(
                        "Your session is moving to another GPU",
                        "Fine-tune a model's host dropped. We're finding another GPU, and it carries on from its last"
                                + " checkpoint if it saved one. Minutes lost with the host aren't charged.",
                        "/session/?id=" + ID));
        samples.put(
                NotificationKind.OUTPUTS_EXPIRING,
                new Message(
                        "Your outputs are kept for 3 more days",
                        "The outputs of Fine-tune a model are deleted after 30 days. Download what you want to keep.",
                        "/session/?id=" + ID + "#outputs"));
        samples.put(
                NotificationKind.MACHINE_OFFLINE,
                new Message(
                        "ASUS ROG Strix went offline during a session",
                        "It was running a session when it lost its connection, so the session moved to another"
                                + " machine. Check that it's on and connected; it takes new work again once it is.",
                        "/hosting/machine/?id=" + ID));
        samples.put(
                NotificationKind.AGENT_UPDATE_NEEDED,
                new Message(
                        "ASUS ROG Strix needs an update",
                        "Its IdleO app is older than we support, so it takes no new work. It updates by itself when"
                                + " the laptop is on and idle.",
                        "/hosting/machine/?id=" + ID));
        samples.put(
                NotificationKind.WEEKLY_EARNINGS,
                new Message(
                        "You earned ₹212.40 this week",
                        "Your machines were rented for 17 h 5 min. Payouts start with the paid pilot.",
                        "/hosting/earnings/"));
        samples.put(
                NotificationKind.PAYOUT,
                new Message(
                        "₹187.00 is on its way to your UPI ID",
                        "This week's payout left us for your UPI ID. Most banks show it within minutes.",
                        "/hosting/earnings/"));
        samples.put(
                NotificationKind.REPORT_ANSWERED,
                new Message(
                        "Your report was answered",
                        "We've given ₹4.92 back to your credit after your report on a Fine-tune a model session."
                                + " It doesn't expire. The host's GPU dropped out twice, and the session shouldn't have"
                                + " been charged for it.",
                        "/account/reports/"));
        samples.put(
                NotificationKind.TOP_UP_CONFIRMED,
                new Message(
                        "₹500.00 added to your credit",
                        "Your payment came through. Your credit is ₹499.40, and the ₹500.00 doesn't expire.",
                        "/credit/"));
        samples.put(
                NotificationKind.NEW_DEVICE_SIGN_IN,
                new Message(
                        "New sign-in to your account",
                        "Someone signed in to your account from a new device. If it was you, there's nothing to do."
                                + " If it wasn't, write to us from Help.",
                        "/account/"));
        samples.put(
                NotificationKind.IMAGE_BLOCKED,
                new Message(
                        "Your image was blocked",
                        "my-training-image:latest can't run on IdleO any more. A newer check found software in it that"
                                + " isn't allowed on IdleO (the acceptable use policy). Sessions of it that hadn't"
                                + " started were cancelled, and you weren't charged for them.",
                        "/account/images/"));
        samples.put(
                NotificationKind.ACCOUNT_AND_DATA,
                new Message(
                        "Your account will be deleted on 4 November",
                        "You asked us to delete your account. Until then you can change your mind from Your data.",
                        "/account/data/"));
        for (var sample : samples.entrySet()) {
            var email = content.of("asha@example.com", sample.getKey(), sample.getValue(), ID);
            Files.writeString(folder.resolve(sample.getKey().slug() + ".html"), email.html());
            Files.writeString(folder.resolve(sample.getKey().slug() + ".txt"), email.text());
        }
        // The emails kept nowhere (a secret link, the last word to an account that's gone) are the same page.
        var direct = content.direct(
                "asha@example.com",
                new Message(
                        "Confirm deleting your account",
                        "Open this link within an hour to delete your account. If you didn't ask, ignore this email.",
                        "/account/delete/confirm/?token=0123456789abcdef"),
                ID);
        Files.writeString(folder.resolve("account-delete-link.html"), direct.html());
        Files.writeString(folder.resolve("account-delete-link.txt"), direct.text());
    }
}
