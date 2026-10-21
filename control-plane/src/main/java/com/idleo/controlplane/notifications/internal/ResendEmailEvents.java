package com.idleo.controlplane.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resend's webhook (resend.com/docs/webhooks): {@code email.bounced} and {@code email.complained} are what we act on.
 * Resend signs each call the Svix way (docs.svix.com/receiving/verifying-payloads/how-manual): {@code svix-signature}
 * holds one or more space-separated {@code v1,<base64>}, each an HMAC-SHA256 of {@code <svix-id>.<svix-timestamp>.<raw
 * body>} under the secret's bytes (the base64 after {@code whsec_}); {@code svix-timestamp} is seconds since the epoch,
 * refused more than {@link #TOLERANCE} from our clock either way, so a captured call can't be replayed later. With no
 * secret configured every call is refused.
 */
@Component
class ResendEmailEvents implements EmailEvents {

    static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String SECRET_PREFIX = "whsec_";

    private final NotificationProperties properties;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    ResendEmailEvents(NotificationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Event read(Map<String, String> headers, byte[] body) {
        var id = headers.getOrDefault("svix-id", "");
        verify(id, headers.getOrDefault("svix-timestamp", ""), headers.getOrDefault("svix-signature", ""), body);
        try {
            var event = json.readTree(body);
            var type = event.path("type").asString("");
            var data = event.path("data");
            var addresses = new ArrayList<String>();
            data.path("to").forEach(to -> addresses.add(to.asString()));
            var kind = switch (type) {
                case "email.bounced" ->
                    "Permanent"
                                    .equalsIgnoreCase(
                                            data.path("bounce").path("type").asString(""))
                            ? Kind.HARD_BOUNCE
                            : Kind.SOFT_BOUNCE;
                case "email.complained" -> Kind.COMPLAINT;
                default -> Kind.OTHER;
            };
            return new Event(id, kind, addresses);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not an event", e);
        }
    }

    private void verify(String id, String timestamp, String signatures, byte[] body) {
        var secret = properties.email().webhookSecret();
        if (secret == null || !secret.startsWith(SECRET_PREFIX)) {
            throw new Unsigned("no webhook secret is configured");
        }
        if (id.isBlank() || timestamp.isBlank() || signatures.isBlank()) {
            throw new Unsigned("unsigned");
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException e) {
            throw new Unsigned("a timestamp that isn't one");
        }
        var skew = Duration.between(Instant.ofEpochSecond(sentAt), Instant.now(clock))
                .abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            throw new Unsigned("signed too long ago, or in the future");
        }
        byte[] expected;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length())), "HmacSHA256"));
            mac.update((id + "." + timestamp.strip() + ".").getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal(body);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Unsigned("the webhook secret can't be used");
        }
        for (var candidate : signatures.strip().split(" ")) {
            if (!candidate.startsWith("v1,")) {
                continue;
            }
            try {
                if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(candidate.substring(3)))) {
                    return;
                }
            } catch (IllegalArgumentException notBase64) {
                // Another candidate may still match.
            }
        }
        throw new Unsigned("the signature doesn't match");
    }
}
