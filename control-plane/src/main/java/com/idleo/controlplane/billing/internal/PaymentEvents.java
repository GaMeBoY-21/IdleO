package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
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
 * What a signed Razorpay event does (ADR 0039; the events are https://razorpay.com/docs/webhooks/payments/, …/orders/,
 * …/refunds/ and …/disputes/). Events may come more than once and out of order, so each is handled once by its event
 * id, and each handler is safe to repeat on its own: a payment is credited once whoever brings it, a failure never
 * undoes a capture, and a refund or a dispute only ever moves to a later state. The event's payload is read for
 * Razorpay's ids and states only, and never stored: it carries the payer's email and phone.
 */
@Component
class PaymentEvents {

    private static final Logger log = LoggerFactory.getLogger(PaymentEvents.class);

    private final TopUps topUps;
    private final RazorpayClient razorpay;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    PaymentEvents(TopUps topUps, RazorpayClient razorpay, JdbcClient jdbc, Clock clock) {
        this.topUps = topUps;
        this.razorpay = razorpay;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The body isn't a Razorpay event. */
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
            log.info("Razorpay event {} ({}) was handled already", id, event);
            return;
        }
        var payload = root.path("payload");
        var payment = payload.path("payment").path("entity");
        switch (event) {
            case "payment.captured", "order.paid" -> captured(payment);
            case "payment.failed" -> failed(payment);
            default -> log.info("Razorpay event {} ({}) needs nothing from us", id, event);
        }
        record(id, event, payload);
    }

    /** A capture, from either event: the payment is fetched from Razorpay's API and checked as Checkout's would be. */
    private void captured(JsonNode paymentNode) {
        if (!paymentNode.isObject()) {
            throw new Unreadable("a payment event without its payment");
        }
        var fromEvent = razorpay.payment(paymentNode);
        var orderId = fromEvent.orderId();
        var topUp = orderId == null ? null : topUps.byOrder(orderId).orElse(null);
        if (topUp == null) {
            // Not one of ours (a payment on Razorpay's own page, say): the reconciliation lists it as missing here.
            log.warn("Razorpay payment {} is for order {}, which isn't a top-up", fromEvent.id(), orderId);
            return;
        }
        RazorpayClient.Payment fetched;
        try {
            fetched = razorpay.fetchPayment(fromEvent.id());
        } catch (RazorpayClient.Refused e) {
            log.error("Razorpay doesn't know payment {}, which its own event named", fromEvent.id());
            return;
        }
        // A fetch carries no card entity unless asked; the event's has the network and last four digits.
        var payment = fetched.cardLast4() == null && fromEvent.cardLast4() != null
                ? new RazorpayClient.Payment(
                        fetched.id(),
                        fetched.amount(),
                        fetched.currency(),
                        fetched.status(),
                        fetched.orderId(),
                        fetched.method(),
                        fetched.captured(),
                        fetched.amountRefunded(),
                        fromEvent.cardNetwork(),
                        fromEvent.cardLast4(),
                        fetched.errorCode(),
                        fetched.errorDescription(),
                        fetched.createdAt())
                : fetched;
        try {
            topUps.credit(topUp, payment, "webhook");
        } catch (ApiProblem refused) {
            // Logged and counted as a mismatch where it was found; the event is still handled, so Razorpay stops.
            log.error("Razorpay payment {} wasn't credited: {}", payment.id(), refused.getMessage());
        }
    }

    private void failed(JsonNode paymentNode) {
        if (!paymentNode.isObject()) {
            throw new Unreadable("a payment event without its payment");
        }
        var payment = razorpay.payment(paymentNode);
        if (payment.orderId() == null) {
            return;
        }
        topUps.byOrder(payment.orderId())
                .ifPresent(topUp -> topUps.failed(topUp, payment.id(), payment.method(), payment.errorDescription()));
    }

    private boolean seen(String eventId) {
        return jdbc.sql("select exists (select 1 from billing.payment_events where event_id = :id)")
                .param("id", eventId)
                .query(Boolean.class)
                .single();
    }

    private void record(String eventId, String event, JsonNode payload) {
        jdbc.sql("""
                        insert into billing.payment_events
                            (event_id, event, payment_id, order_id, refund_id, dispute_id, received_at)
                        values (:eventId, :event, :paymentId, :orderId, :refundId, :disputeId, :now)
                        on conflict (event_id) do nothing
                        """)
                .param("eventId", eventId)
                .param("event", event)
                .param("paymentId", id(payload.path("payment").path("entity")))
                .param("orderId", orderOf(payload))
                .param("refundId", id(payload.path("refund").path("entity")))
                .param("disputeId", id(payload.path("dispute").path("entity")))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private static @Nullable String orderOf(JsonNode payload) {
        var order = id(payload.path("order").path("entity"));
        if (order != null) {
            return order;
        }
        var fromPayment = payload.path("payment").path("entity").path("order_id");
        return fromPayment.isString() ? fromPayment.asString() : null;
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
