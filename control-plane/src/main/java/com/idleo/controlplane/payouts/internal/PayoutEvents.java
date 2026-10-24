package com.idleo.controlplane.payouts.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a signed RazorpayX event does (ADR 0040; https://razorpay.com/docs/webhooks/payouts/ and
 * …/webhooks/account-validation/). Events may come more than once and out of order
 * (https://razorpay.com/docs/webhooks/validate-test/), so each is handled once by its event id, and a payout's status
 * is the one RazorpayX's API gives when we fetch it, which only ever moves forward here. Nothing of the payload is
 * kept but RazorpayX's ids: it names the host's UPI ID.
 */
@Component
class PayoutEvents {

    private static final Logger log = LoggerFactory.getLogger(PayoutEvents.class);

    private final PayoutService payouts;
    private final PayoutMethods methods;
    private final RazorpayXClient razorpayx;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    PayoutEvents(
            PayoutService payouts, PayoutMethods methods, RazorpayXClient razorpayx, JdbcClient jdbc, Clock clock) {
        this.payouts = payouts;
        this.methods = methods;
        this.razorpayx = razorpayx;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The body isn't a RazorpayX event. */
    static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String message) {
            super(message);
        }
    }

    /** Handles one signed event; {@code eventId} is {@code x-razorpay-event-id}. */
    void handle(@Nullable String eventId, byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            throw new Unreadable("not JSON");
        }
        var event = root.path("event").asString("");
        if (!"event".equals(root.path("entity").asString("")) || event.isEmpty()) {
            throw new Unreadable("not an event");
        }
        var id = eventId == null || eventId.isBlank() ? "body:" + sha256(body) : eventId.strip();
        if (seen(id)) {
            log.info("RazorpayX event {} ({}) was handled already", id, event);
            return;
        }
        var payload = root.path("payload");
        var payout = payload.path("payout").path("entity");
        var validation = payload.path("fund_account.validation").path("entity");
        switch (event) {
            case "payout.queued",
                    "payout.pending",
                    "payout.initiated",
                    "payout.processed",
                    "payout.updated",
                    "payout.reversed",
                    "payout.failed",
                    "payout.rejected" -> payout(payout);
            case "fund_account.validation.completed", "fund_account.validation.failed" -> {
                if (!validation.isObject()) {
                    throw new Unreadable("a validation event without its validation");
                }
                methods.applyValidation(razorpayx.validation(validation));
            }
            default -> log.info("RazorpayX event {} ({}) needs nothing from us", id, event);
        }
        record(id, event, payout, validation);
    }

    /** A payout's news: RazorpayX's own record of it decides, fetched, not the event's copy. */
    private void payout(JsonNode node) {
        if (!node.isObject()) {
            throw new Unreadable("a payout event without its payout");
        }
        var fromEvent = razorpayx.payout(node);
        RazorpayXClient.Payout fetched;
        try {
            fetched = razorpayx.fetchPayout(fromEvent.id());
        } catch (RazorpayXClient.Refused refused) {
            log.error("RazorpayX doesn't know payout {}, which its own event named", fromEvent.id());
            return;
        }
        payouts.applyRazorpayX(fetched);
    }

    private boolean seen(String eventId) {
        return jdbc.sql("select exists (select 1 from payouts.events where event_id = :id)")
                .param("id", eventId)
                .query(Boolean.class)
                .single();
    }

    private void record(String eventId, String event, JsonNode payout, JsonNode validation) {
        jdbc.sql("""
                        insert into payouts.events (event_id, event, payout_id, validation_id, received_at)
                        values (:eventId, :event, :payoutId, :validationId, :now)
                        on conflict (event_id) do nothing
                        """)
                .param("eventId", eventId)
                .param("event", event)
                .param("payoutId", id(payout))
                .param("validationId", id(validation))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private static @Nullable String id(JsonNode entity) {
        var id = entity.path("id");
        return id.isString() ? id.asString() : null;
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
