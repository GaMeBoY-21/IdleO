package com.idleo.controlplane.razorpay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stateful fake of Razorpay's API (ADR 0039), as a WireMock extension, for the tests and the local stack. Every
 * answer and every webhook is built from Razorpay's own documented samples (src/test/resources/razorpay/), with only
 * their values changed: ids, amounts, states, times and notes. Nothing here is in the control plane.
 *
 * <p>It keeps orders, payments and refunds in memory and answers the calls {@code RazorpayClient} makes: create an
 * order, fetch a payment, list payments and refunds, refund a payment. A test pays an order ({@link #pay}), leaves one
 * authorised ({@link #authorize}) or fails one ({@link #fail}), and builds signed webhook events ({@link #event}). On
 * the local stack, the browser's fake Checkout ({@code /v1/checkout.js}) pays through {@code /_fake/pay}, and the fake
 * then sends the documented webhooks to the control plane, signed with the webhook secret, after {@code
 * webhookDelayMillis}.
 */
public final class FakeRazorpay implements AutoCloseable {

    public static final String KEY_ID = "rzp_test_idleofake00001";
    public static final String KEY_SECRET = "fake-razorpay-key-secret";
    public static final String WEBHOOK_SECRET = "fake-razorpay-webhook-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WireMockServer server;
    private final Map<String, ObjectNode> orders = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> payments = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> refunds = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final HttpClient http = HttpClient.newHttpClient();

    /** What a new refund is: {@code processed} at once, {@code pending} (a test settles it), or {@code failed}. */
    public volatile String refundOutcome = "processed";
    /** Where the local stack's fake sends webhooks; null in tests, which post their own. */
    public volatile @Nullable URI webhookTarget;

    public volatile long webhookDelayMillis;

    public FakeRazorpay(int port) {
        server = new WireMockServer(options().port(port).extensions(new Behaviour()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withTransformers(Behaviour.NAME)));
    }

    public String url() {
        return "http://localhost:" + server.port();
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.stop();
    }

    /** A payment the fake made, and Checkout's signature for it. */
    public record Paid(String paymentId, String orderId, String signature) {}

    /** A webhook's raw body and its {@code X-Razorpay-Signature}, and the event id to send with it. */
    public record Signed(String body, String signature, String eventId) {}

    /** Pays the order in full, captured at once (auto-capture), by {@code method}: upi, card or netbanking. */
    public Paid pay(String orderId, String method) {
        var payment = newPayment(orderId, method, "captured");
        return new Paid(text(payment, "id"), orderId, checkoutSignature(orderId, text(payment, "id")));
    }

    /** A payment Razorpay authorised but didn't capture (auto-capture off, or not yet). */
    public Paid authorize(String orderId, String method) {
        var payment = newPayment(orderId, method, "authorized");
        return new Paid(text(payment, "id"), orderId, checkoutSignature(orderId, text(payment, "id")));
    }

    /** A failed attempt at the order; returns the payment's id. */
    public String fail(String orderId, String method) {
        return text(newPayment(orderId, method, "failed"), "id");
    }

    /** Captures an authorised payment, as Razorpay does later. */
    public void capture(String paymentId) {
        var payment = payments.get(paymentId);
        payment.put("status", "captured");
        payment.put("captured", true);
        orders.get(text(payment, "order_id")).put("status", "paid");
    }

    /** Changes a payment's amount or currency behind our back, for the mismatch tests. */
    public void tamper(String paymentId, long amount, String currency) {
        payments.get(paymentId).put("amount", amount).put("currency", currency);
    }

    public ObjectNode payment(String paymentId) {
        return payments.get(paymentId);
    }

    public ObjectNode order(String orderId) {
        return orders.get(orderId);
    }

    /** The orders made, oldest first. */
    public List<ObjectNode> orders() {
        var all = new ArrayList<>(orders.values());
        all.sort((a, b) ->
                Long.compare(a.path("created_at").asLong(), b.path("created_at").asLong()));
        return all;
    }

    public ObjectNode refund(String refundId) {
        return refunds.get(refundId);
    }

    public List<ObjectNode> refundsOf(String paymentId) {
        return refunds.values().stream()
                .filter(refund -> paymentId.equals(text(refund, "payment_id")))
                .toList();
    }

    /** Settles a pending refund: {@code processed} or {@code failed}. */
    public void settleRefund(String refundId, String status) {
        refunds.get(refundId).put("status", status);
    }

    /** Forgets a payment, so it's missing on Razorpay's side (the reconciliation's test). */
    public void forget(String paymentId) {
        payments.remove(paymentId);
    }

    public void reset() {
        orders.clear();
        payments.clear();
        refunds.clear();
        refundOutcome = "processed";
    }

    /** Checkout's signature: HMAC-SHA256 of {@code order_id|payment_id} under the key secret. */
    public static String checkoutSignature(String orderId, String paymentId) {
        return hmac(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    /** A webhook's signature under {@code secret}: HMAC-SHA256 of the raw body. */
    public static String webhookSignature(String body, String secret) {
        return hmac(secret, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A signed webhook event about a payment, from the documented sample: {@code payment.captured}, {@code order.paid},
     * {@code payment.authorized} or {@code payment.failed}.
     */
    public Signed event(String event, String paymentId) {
        var payment = Objects.requireNonNull(payments.get(paymentId), "no payment " + paymentId);
        var method = text(payment, "method");
        var sample = switch (event) {
            case "payment.captured" -> "webhook-payment-captured-" + sampleMethod(method);
            case "payment.authorized" -> "webhook-payment-authorized-netbanking";
            case "payment.failed" ->
                "upi".equals(method) ? "webhook-payment-failed-upi" : "webhook-payment-failed-netbanking";
            case "order.paid" -> "webhook-order-paid";
            default -> throw new IllegalArgumentException("no sample for " + event);
        };
        var root = sample(sample);
        var entity = (ObjectNode) root.path("payload").path("payment").path("entity");
        copyPayment(payment, entity);
        if ("order.paid".equals(event)) {
            var order = (ObjectNode) root.path("payload").path("order").path("entity");
            var stored = orders.get(text(payment, "order_id"));
            order.put("id", text(stored, "id"))
                    .put("amount", stored.path("amount").asLong())
                    .put("amount_paid", stored.path("amount").asLong())
                    .put("amount_due", 0)
                    .put("receipt", text(stored, "receipt"))
                    .put("status", "paid");
        }
        root.put("created_at", Instant.now().getEpochSecond());
        return signed(root, WEBHOOK_SECRET);
    }

    /** A signed refund event ({@code refund.processed} or {@code refund.failed}), from the documented sample. */
    public Signed refundEvent(String event, String refundId) {
        var refund = Objects.requireNonNull(refunds.get(refundId), "no refund " + refundId);
        var root = sample(
                switch (event) {
                    case "refund.created" -> "webhook-refund-created";
                    case "refund.processed" -> "webhook-refund-processed";
                    case "refund.failed" -> "webhook-refund-failed";
                    default -> throw new IllegalArgumentException("no sample for " + event);
                });
        var entity = (ObjectNode) root.path("payload").path("refund").path("entity");
        entity.put("id", text(refund, "id"))
                .put("payment_id", text(refund, "payment_id"))
                .put("amount", refund.path("amount").asLong())
                .put("status", event.substring("refund.".length()).replace("created", "pending"))
                .put("receipt", text(refund, "receipt"));
        copyPayment(payments.get(text(refund, "payment_id")), (ObjectNode)
                root.path("payload").path("payment").path("entity"));
        return signed(root, WEBHOOK_SECRET);
    }

    /**
     * A signed chargeback event ({@code payment.dispute.created}, {@code .won}, {@code .lost}, {@code .closed}), from the
     * documented sample, for {@code amount} paise of the payment, evidence due at {@code respondBy}.
     */
    public Signed disputeEvent(String event, String disputeId, String paymentId, long amount, Instant respondBy) {
        var root = sample(
                switch (event) {
                    case "payment.dispute.created" -> "webhook-payment-dispute-created";
                    case "payment.dispute.won" -> "webhook-payment-dispute-won";
                    case "payment.dispute.lost" -> "webhook-payment-dispute-lost";
                    case "payment.dispute.closed" -> "webhook-payment-dispute-closed";
                    case "payment.dispute.under_review" -> "webhook-payment-dispute-under-review";
                    case "payment.dispute.action_required" -> "webhook-payment-dispute-action-required";
                    default -> throw new IllegalArgumentException("no sample for " + event);
                });
        copyPayment(payments.get(paymentId), (ObjectNode)
                root.path("payload").path("payment").path("entity"));
        var dispute = (ObjectNode) root.path("payload").path("dispute").path("entity");
        dispute.put("id", disputeId)
                .put("payment_id", paymentId)
                .put("amount", amount)
                .put("respond_by", respondBy.getEpochSecond());
        if ("payment.dispute.lost".equals(event)) {
            dispute.put("amount_deducted", amount);
        }
        ((ObjectNode) dispute.path("evidence")).put("amount", amount);
        return signed(root, WEBHOOK_SECRET);
    }

    /** The same event signed with another secret, for the signature tests. */
    public static Signed resign(Signed signed, String secret) {
        return new Signed(signed.body(), webhookSignature(signed.body(), secret), signed.eventId());
    }

    private Signed signed(ObjectNode root, String secret) {
        var body = JSON.writeValueAsString(root);
        return new Signed(body, webhookSignature(body, secret), "evt_" + nextId());
    }

    private static void copyPayment(ObjectNode from, ObjectNode to) {
        for (var field : List.of(
                "id",
                "amount",
                "currency",
                "status",
                "order_id",
                "method",
                "captured",
                "amount_refunded",
                "created_at",
                "error_code",
                "error_description")) {
            if (from.has(field)) {
                to.set(field, from.get(field));
            }
        }
    }

    private ObjectNode newPayment(String orderId, String method, String status) {
        var order = Objects.requireNonNull(orders.get(orderId), "no order " + orderId);
        var payment = (ObjectNode) sample(
                        "failed".equals(status)
                                ? ("upi".equals(method)
                                        ? "webhook-payment-failed-upi"
                                        : "webhook-payment-failed-netbanking")
                                : "webhook-payment-captured-" + sampleMethod(method))
                .path("payload")
                .path("payment")
                .path("entity")
                .deepCopy();
        var id = "pay_" + nextId();
        payment.put("id", id)
                .put("order_id", orderId)
                .put("amount", order.path("amount").asLong())
                .put("currency", "INR")
                .put("status", status)
                .put("captured", "captured".equals(status))
                .put("amount_refunded", 0)
                .put("created_at", Instant.now().getEpochSecond());
        payment.set("notes", order.path("notes"));
        payment.putNull("refund_status");
        payments.put(id, payment);
        order.put("attempts", order.path("attempts").asInt() + 1);
        order.put("status", "captured".equals(status) ? "paid" : "attempted");
        return payment;
    }

    private static String sampleMethod(String method) {
        return switch (method) {
            case "card" -> "card";
            case "upi" -> "upi";
            default -> "netbanking";
        };
    }

    private String nextId() {
        // Razorpay's ids are 14 characters after the prefix; these are unique for the fake's life.
        return String.format("Fk%012d", sequence.incrementAndGet());
    }

    static ObjectNode sample(String name) {
        try (InputStream in = FakeRazorpay.class.getResourceAsStream("/razorpay/" + name + ".json")) {
            return (ObjectNode) JSON.readTree(Objects.requireNonNull(in, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String hmac(String secret, byte[] message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString();
    }

    /** Sends a signed event to the local stack's control plane, as Razorpay would, after the delay. */
    void send(Signed signed) {
        var target = webhookTarget;
        if (target == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(webhookDelayMillis);
                http.send(
                        HttpRequest.newBuilder(target)
                                .header("Content-Type", "application/json")
                                .header("X-Razorpay-Signature", signed.signature())
                                .header("x-razorpay-event-id", signed.eventId())
                                .POST(HttpRequest.BodyPublishers.ofString(signed.body()))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                System.err.println("fake-razorpay: couldn't send a webhook to " + target + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Every request to the fake, answered from its state. */
    private final class Behaviour implements ResponseDefinitionTransformerV2 {

        static final String NAME = "razorpay";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            var request = event.getRequest();
            var path = URI.create(request.getUrl()).getPath();
            var query = query(URI.create(request.getUrl()).getRawQuery());
            var method = request.getMethod().getName();
            try {
                if (path.startsWith("/_fake/") || path.equals("/v1/checkout.js")) {
                    return browser(method, path, request.getBodyAsString());
                }
                if (!authorised(request.getHeader("Authorization"))) {
                    return error(401, "Authentication failed");
                }
                if (method.equals("POST") && path.equals("/v1/orders")) {
                    return createOrder(JSON.readTree(request.getBodyAsString()));
                }
                if (method.equals("GET") && path.equals("/v1/payments")) {
                    return collection(payments.values(), query);
                }
                if (method.equals("GET") && path.equals("/v1/refunds")) {
                    return collection(refunds.values(), query);
                }
                if (method.equals("GET") && path.startsWith("/v1/payments/")) {
                    var payment = payments.get(path.substring("/v1/payments/".length()));
                    return payment == null ? sampleError("api-payment-fetch-error", 400) : json(200, payment);
                }
                if (method.equals("POST") && path.startsWith("/v1/payments/") && path.endsWith("/refund")) {
                    var id = path.substring("/v1/payments/".length(), path.length() - "/refund".length());
                    return createRefund(id, JSON.readTree(request.getBodyAsString()));
                }
                return error(404, "The requested URL was not found on the server.");
            } catch (RuntimeException e) {
                return error(400, "The request couldn't be read: " + e.getMessage());
            }
        }

        private boolean authorised(@Nullable String header) {
            var expected = "Basic "
                    + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
            return expected.equals(header);
        }

        private ResponseDefinition createOrder(JsonNode body) {
            var receipt = body.path("receipt").asString();
            if (orders.values().stream().anyMatch(order -> receipt.equals(text(order, "receipt")))) {
                return error(400, "Duplicate receipt");
            }
            var order = sample("api-order-create");
            var amount = body.path("amount").asLong();
            order.put("id", "order_" + nextId())
                    .put("amount", amount)
                    .put("amount_due", amount)
                    .put("amount_paid", 0)
                    .put("attempts", 0)
                    .put("currency", body.path("currency").asString())
                    .put("receipt", receipt)
                    .put("status", "created")
                    .put("created_at", Instant.now().getEpochSecond());
            order.set("notes", body.path("notes"));
            orders.put(text(order, "id"), order);
            return json(200, order);
        }

        private ResponseDefinition createRefund(String paymentId, JsonNode body) {
            var payment = payments.get(paymentId);
            if (payment == null) {
                return sampleError("api-payment-fetch-error", 400);
            }
            var receipt = body.path("receipt").asString();
            if (!receipt.isEmpty()
                    && refundsOf(paymentId).stream().anyMatch(refund -> receipt.equals(text(refund, "receipt")))) {
                // The documentation's own words for a reused receipt.
                return error(400, "Duplicate receipt found for this refund request.");
            }
            long amount = body.path("amount").asLong(payment.path("amount").asLong());
            long refunded = payment.path("amount_refunded").asLong();
            if (amount + refunded > payment.path("amount").asLong()) {
                return error(400, "The refund amount provided is greater than amount captured");
            }
            var refund = sample("api-refund-create");
            refund.put("id", "rfnd_" + nextId())
                    .put("amount", amount)
                    .put("receipt", receipt)
                    .put("currency", "INR")
                    .put("payment_id", paymentId)
                    .put(
                            "status",
                            "failed".equals(refundOutcome)
                                    ? "failed"
                                    : "processed".equals(refundOutcome) ? "processed" : "pending")
                    .put("created_at", Instant.now().getEpochSecond());
            refund.set("notes", body.path("notes"));
            refunds.put(text(refund, "id"), refund);
            if (!"failed".equals(refundOutcome)) {
                payment.put("amount_refunded", refunded + amount);
                payment.put(
                        "refund_status",
                        refunded + amount == payment.path("amount").asLong() ? "full" : "partial");
            }
            return json(200, refund);
        }

        private ResponseDefinition collection(java.util.Collection<ObjectNode> all, Map<String, String> query) {
            long from = Long.parseLong(query.getOrDefault("from", "0"));
            long to = Long.parseLong(query.getOrDefault("to", String.valueOf(Long.MAX_VALUE)));
            int count = Integer.parseInt(query.getOrDefault("count", "10"));
            int skip = Integer.parseInt(query.getOrDefault("skip", "0"));
            var matching = all.stream()
                    .filter(item -> item.path("created_at").asLong() >= from
                            && item.path("created_at").asLong() <= to)
                    .sorted((a, b) -> Long.compare(
                            b.path("created_at").asLong(), a.path("created_at").asLong()))
                    .skip(skip)
                    .limit(count)
                    .toList();
            var root = JSON.createObjectNode();
            root.put("entity", "collection");
            root.put("count", matching.size());
            ArrayNode items = root.putArray("items");
            matching.forEach(items::add);
            return json(200, root);
        }

        /** The browser's fake Checkout, and what it calls; none of this is Razorpay's API. */
        private ResponseDefinition browser(String method, String path, String body) {
            if (method.equals("OPTIONS")) {
                return cors(aResponse().withStatus(204)).build();
            }
            if (path.equals("/v1/checkout.js")) {
                try (InputStream in = FakeRazorpay.class.getResourceAsStream("/razorpay/fake-checkout.js")) {
                    var script = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)
                            .replace("__FAKE_URL__", url());
                    return cors(aResponse()
                                    .withStatus(200)
                                    .withHeader("Content-Type", "text/javascript")
                                    .withBody(script))
                            .build();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            if (method.equals("POST") && path.equals("/_fake/pay")) {
                var asked = JSON.readTree(body);
                var orderId = asked.path("order_id").asString();
                var outcome = asked.path("outcome").asString("captured");
                var payMethod = asked.path("method").asString("upi");
                var answer = JSON.createObjectNode();
                if ("failed".equals(outcome)) {
                    var paymentId = fail(orderId, payMethod);
                    var payment = payments.get(paymentId);
                    var error = answer.putObject("error");
                    error.put("code", text(payment, "error_code"));
                    error.put("description", text(payment, "error_description"));
                    error.putObject("metadata").put("order_id", orderId).put("payment_id", paymentId);
                    send(event("payment.failed", paymentId));
                } else {
                    var paid = pay(orderId, payMethod);
                    answer.put("razorpay_payment_id", paid.paymentId());
                    answer.put("razorpay_order_id", paid.orderId());
                    answer.put("razorpay_signature", paid.signature());
                    send(event("payment.captured", paid.paymentId()));
                    send(event("order.paid", paid.paymentId()));
                }
                return cors(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(JSON.writeValueAsString(answer)))
                        .build();
            }
            return error(404, "No such fake endpoint.");
        }

        private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder cors(
                com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder builder) {
            return builder.withHeader("Access-Control-Allow-Origin", "*")
                    .withHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    .withHeader("Access-Control-Allow-Headers", "Content-Type");
        }

        private ResponseDefinition json(int status, JsonNode body) {
            return aResponse()
                    .withStatus(status)
                    .withHeaders(new HttpHeaders(new HttpHeader("Content-Type", "application/json")))
                    .withBody(JSON.writeValueAsString(body))
                    .build();
        }

        private ResponseDefinition sampleError(String name, int status) {
            return json(status, sample(name));
        }

        /** An error in the documented shape ({@code api-order-create-error.json}), with our description. */
        private ResponseDefinition error(int status, String description) {
            var error = sample("api-order-create-error");
            ((ObjectNode) error.path("error")).put("description", description);
            return json(status, error);
        }

        private Map<String, String> query(@Nullable String raw) {
            var map = new LinkedHashMap<String, String>();
            if (raw == null || raw.isBlank()) {
                return map;
            }
            for (var pair : raw.split("&")) {
                var at = pair.indexOf('=');
                if (at > 0) {
                    map.put(
                            URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
                }
            }
            return map;
        }
    }

    /** For tests that need an id nobody has. */
    public static String unknownPaymentId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
