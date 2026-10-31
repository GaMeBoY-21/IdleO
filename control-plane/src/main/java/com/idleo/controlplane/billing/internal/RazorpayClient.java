package com.idleo.controlplane.billing.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The calls we make to Razorpay's API (ADR 0039), each with only the fields its documentation lists: create an order
 * (https://razorpay.com/docs/api/orders/create/), fetch a payment (…/api/payments/fetch-with-id/), list a period's
 * payments and refunds (…/api/payments/fetch-all-payments/, …/api/refunds/fetch-all/), and refund a payment
 * (…/api/refunds/create-normal/, with an idempotency key: …/api/refunds/normal-refunds-idempotent/). HTTP basic
 * authentication with the key id and secret; amounts in paise. A call Razorpay refuses throws {@link Refused} with its
 * HTTP status, Razorpay's error code and description; one that can't be made throws {@link Unreachable}.
 */
@Component
@EnableConfigurationProperties(RazorpayProperties.class)
class RazorpayClient {

    /** The most a list call returns at once (the documentation's maximum). */
    static final int PAGE = 100;

    private final RazorpayProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    RazorpayClient(RazorpayProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    /** An order (https://razorpay.com/docs/api/orders/create/). */
    record Order(
            String id,
            long amount,
            String currency,
            @Nullable String receipt,
            String status) {}

    /**
     * A payment (https://razorpay.com/docs/api/payments/fetch-with-id/): {@code card} is there only when Razorpay
     * includes the card entity (a webhook does), and then only its network and last four digits are kept.
     */
    record Payment(
            String id,
            long amount,
            String currency,
            String status,
            @Nullable String orderId,
            @Nullable String method,
            boolean captured,
            long amountRefunded,
            @Nullable String cardNetwork,
            @Nullable String cardLast4,
            @Nullable String errorCode,
            @Nullable String errorDescription,
            Instant createdAt) {}

    /** A refund (https://razorpay.com/docs/api/refunds/create-normal/): pending, processed or failed. */
    record Refund(
            String id,
            String paymentId,
            long amount,
            String currency,
            String status,
            @Nullable String receipt) {}

    /** Razorpay answered with an error. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final int status;
        final @Nullable String code;

        Refused(int status, @Nullable String code, String description) {
            super(description);
            this.status = status;
            this.code = code;
        }
    }

    /** Razorpay couldn't be reached, or answered with something that isn't its API's. */
    static final class Unreachable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreachable(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    Order createOrder(long amountPaise, String receipt, Map<String, String> notes) {
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", amountPaise);
        body.put("currency", "INR");
        body.put("receipt", receipt);
        body.put("notes", notes);
        var node = call("POST", "/v1/orders", body);
        return new Order(
                text(node, "id"),
                node.path("amount").asLong(),
                text(node, "currency"),
                optional(node, "receipt"),
                text(node, "status"));
    }

    Payment fetchPayment(String paymentId) {
        return payment(call("GET", "/v1/payments/" + encode(paymentId), null));
    }

    /** Every payment created from {@code from} to {@code to}, a page of 100 at a time. */
    List<Payment> payments(Instant from, Instant to) {
        var all = new ArrayList<Payment>();
        for (int skip = 0; ; skip += PAGE) {
            var page = call(
                    "GET",
                    "/v1/payments?from=" + from.getEpochSecond() + "&to=" + to.getEpochSecond() + "&count=" + PAGE
                            + "&skip=" + skip,
                    null);
            var items = page.path("items");
            items.forEach(item -> all.add(payment(item)));
            if (items.size() < PAGE) {
                return all;
            }
        }
    }

    /** Every refund created from {@code from} to {@code to}, a page of 100 at a time. */
    List<Refund> refunds(Instant from, Instant to) {
        var all = new ArrayList<Refund>();
        for (int skip = 0; ; skip += PAGE) {
            var page = call(
                    "GET",
                    "/v1/refunds?from=" + from.getEpochSecond() + "&to=" + to.getEpochSecond() + "&count=" + PAGE
                            + "&skip=" + skip,
                    null);
            var items = page.path("items");
            items.forEach(item -> all.add(refund(item)));
            if (items.size() < PAGE) {
                return all;
            }
        }
    }

    /**
     * Refunds {@code amountPaise} of a payment, at the normal speed, always to where it was paid from. {@code receipt}
     * is ours (letters, digits and underscores, at least 10 of them) and goes as the request's idempotency key too, in
     * {@code X-Refund-Idempotency}: the same receipt with the same body, which the caller makes from the same part every
     * time, refunds nothing twice however often it's sent, so a request whose answer was lost can be sent again.
     * Razorpay answers 409 while a request with the key is still being worked on: send it again later, as it was.
     */
    Refund refund(String paymentId, long amountPaise, String receipt, Map<String, String> notes) {
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", amountPaise);
        body.put("speed", "normal");
        body.put("receipt", receipt);
        body.put("notes", notes);
        return refund(call(
                "POST",
                "/v1/payments/" + encode(paymentId) + "/refund",
                body,
                Map.of("X-Refund-Idempotency", receipt)));
    }

    /** The refunds of one payment (https://razorpay.com/docs/api/refunds/fetch-multiple-refund-payment/). */
    List<Refund> refundsOf(String paymentId) {
        var all = new ArrayList<Refund>();
        call("GET", "/v1/payments/" + encode(paymentId) + "/refunds?count=" + PAGE, null)
                .path("items")
                .forEach(item -> all.add(refund(item)));
        return all;
    }

    Payment payment(JsonNode node) {
        var card = node.path("card");
        var created = node.path("created_at");
        return new Payment(
                text(node, "id"),
                node.path("amount").asLong(),
                text(node, "currency"),
                text(node, "status"),
                optional(node, "order_id"),
                optional(node, "method"),
                node.path("captured").asBoolean(false),
                node.path("amount_refunded").asLong(0),
                card.isObject() ? optional(card, "network") : null,
                card.isObject() ? optional(card, "last4") : null,
                optional(node, "error_code"),
                optional(node, "error_description"),
                created.isNumber() ? Instant.ofEpochSecond(created.asLong()) : Instant.EPOCH);
    }

    Refund refund(JsonNode node) {
        return new Refund(
                text(node, "id"),
                text(node, "payment_id"),
                node.path("amount").asLong(),
                text(node, "currency"),
                text(node, "status"),
                optional(node, "receipt"));
    }

    private JsonNode call(String method, String path, @Nullable Object body) {
        return call(method, path, body, Map.of());
    }

    private JsonNode call(String method, String path, @Nullable Object body, Map<String, String> headers) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Razorpay isn't configured: RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET");
        }
        var credentials = Base64.getEncoder()
                .encodeToString((properties.keyId() + ":" + properties.keySecret()).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(
                        URI.create(properties.apiUrl().toString().replaceAll("/+$", "") + path))
                .timeout(properties.timeout())
                .header("Authorization", "Basic " + credentials)
                .header("Accept", "application/json");
        headers.forEach(request::header);
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new Unreachable("Razorpay couldn't be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("interrupted while calling Razorpay", e);
        }
        JsonNode node;
        try {
            node = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new Unreachable("Razorpay answered " + response.statusCode() + " with something that isn't JSON", e);
        }
        if (response.statusCode() >= 400) {
            var error = node.path("error");
            throw new Refused(
                    response.statusCode(),
                    optional(error, "code"),
                    Objects.requireNonNullElse(optional(error, "description"), "Razorpay refused the request"));
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        var value = optional(node, field);
        if (value == null) {
            throw new Unreachable("Razorpay's answer has no " + field, null);
        }
        return value;
    }

    private static @Nullable String optional(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asString().isEmpty() ? null : value.asString();
    }

    private static String encode(String id) {
        return URLEncoder.encode(id, StandardCharsets.UTF_8);
    }
}
