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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * A stateful fake of RazorpayX (ADR 0040), as a WireMock extension, for the tests and the local stack. Every answer and
 * every webhook is built from RazorpayX's documented samples (src/test/resources/razorpayx/), with only their values
 * changed. Nothing here is in the control plane.
 *
 * <p>It answers the calls {@code RazorpayXClient} makes: check a UPI ID, fetch a check, create a payout (refusing one
 * without {@code X-Payout-Idempotency}, and answering a repeat of the same key and body with the same payout), fetch
 * payouts and the balance. A UPI ID's local part steers the check and the payouts, for the tests and the runbook's
 * journeys:
 *
 * <ul>
 *   <li>{@code invalid@…}: the bank says it isn't active;
 *   <li>{@code lowmatch@…}: active, but its registered name matches the PAN's at 42;
 *   <li>{@code slow@…}: the check answers {@code created}, and completes when the test says ({@link #completeCheck});
 *   <li>{@code payoutfails@…}: payouts to it fail; {@code reversed@…}: they're processed and then reversed;
 *   <li>anything else: active, registered to the name sent, a score of 100, and payouts processing.
 * </ul>
 *
 * A payout is {@code processing} when the balance covers it and {@code queued} when it doesn't. A test moves it on
 * ({@link #settle}) and posts the signed event it gets back; the local stack's fake, given {@code autoSettle}, moves
 * each new payout to its end after {@code webhookDelayMillis} and sends the documented webhooks itself.
 */
public final class FakeRazorpayX implements AutoCloseable {

    public static final String KEY_ID = "rzp_test_idleofakex00001";
    public static final String KEY_SECRET = "fake-razorpayx-key-secret";
    public static final String WEBHOOK_SECRET = "fake-razorpayx-webhook-secret";
    /** The documentation's sample account number. */
    public static final String ACCOUNT_NUMBER = "7878780080316316";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WireMockServer server;
    private final Map<String, ObjectNode> validations = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> payouts = new ConcurrentHashMap<>();
    /** X-Payout-Idempotency to the payout it made and the body it was made with. */
    private final Map<String, String[]> idempotency = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();
    private final HttpClient http = HttpClient.newHttpClient();

    /** What the business account can pay out, in paise. */
    public volatile long balance = 100_000_000L;
    /** Where the local stack's fake sends webhooks; null in tests, which post their own. */
    public volatile @Nullable URI webhookTarget;

    public volatile long webhookDelayMillis;
    /** The local stack's: each new payout moves to its end by itself, and its webhooks are sent. */
    public volatile boolean autoSettle;

    public volatile java.time.Clock clock = java.time.Clock.systemUTC();

    public FakeRazorpayX(int port) {
        server = new WireMockServer(options().port(port).extensions(new Behaviour()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withTransformers(Behaviour.NAME)));
    }

    public String url() {
        return "http://localhost:" + server.port();
    }

    @Override
    public void close() {
        server.stop();
    }

    /** A webhook's raw body and its {@code X-Razorpay-Signature}, and the event id to send with it. */
    public record Signed(String body, String signature, String eventId) {}

    public ObjectNode payout(String payoutId) {
        return payouts.get(payoutId);
    }

    /** The payouts made, oldest first. */
    public List<ObjectNode> payouts() {
        var all = new ArrayList<>(payouts.values());
        all.sort((a, b) -> text(a, "id").compareTo(text(b, "id")));
        return all;
    }

    public ObjectNode validation(String validationId) {
        return validations.get(validationId);
    }

    /** Moves a payout to {@code status} (processed, reversed, failed, rejected, cancelled, queued or processing). */
    public Signed settle(String payoutId, String status) {
        var payout = Objects.requireNonNull(payouts.get(payoutId), "no payout " + payoutId);
        move(payout, status);
        return event("payout." + eventFor(status), payoutId);
    }

    /** Moves a payout without a webhook: RazorpayX's state ahead of ours, for the backstop and reconciliation tests. */
    public void quietly(String payoutId, String status) {
        move(Objects.requireNonNull(payouts.get(payoutId)), status);
    }

    private void move(ObjectNode payout, String status) {
        payout.put("status", status);
        var details = sample("webhook-payout-" + sampleFor(status))
                .path("payload")
                .path("payout")
                .path("entity")
                .path("status_details");
        payout.set("status_details", details.deepCopy());
        if (status.equals("processed")) {
            payout.put("utr", String.format("%012d", sequence.incrementAndGet()));
        }
        if (status.equals("reversed") || status.equals("failed")) {
            balance += payout.path("amount").asLong();
        }
    }

    /** Changes a payout's amount behind our back, for the mismatch test. */
    public void tamper(String payoutId, long amount) {
        payouts.get(payoutId).put("amount", amount);
    }

    /** Forgets a payout, so it's missing on RazorpayX's side. */
    public void forget(String payoutId) {
        payouts.remove(payoutId);
    }

    /** A payout made outside our API (from RazorpayX's dashboard, say): returns its id. */
    public String outsidePayout(long amount) {
        var payout = sample("api-payout-create-vpa");
        var id = "pout_" + nextId();
        payout.put("id", id)
                .put("amount", amount)
                .put("status", "processed")
                .put("reference_id", "dashboard " + id)
                .put("created_at", clock.instant().getEpochSecond());
        payouts.put(id, payout);
        return id;
    }

    /** Completes a check that answered {@code created}: {@code active} with a score, or {@code invalid}. */
    public Signed completeCheck(String validationId, String accountStatus, int score) {
        var validation = Objects.requireNonNull(validations.get(validationId));
        validation.put("status", "completed");
        var results = (ObjectNode) validation.path("validation_results");
        results.put("account_status", accountStatus).put("name_match_score", score);
        var root = sample("webhook-validation-completed-vpa");
        var entity = (ObjectNode)
                root.path("payload").path("fund_account.validation").path("entity");
        entity.put("id", validationId).put("status", "completed");
        ((ObjectNode) entity.path("results"))
                .put("account_status", accountStatus)
                .put("registered_name", text(results, "registered_name"))
                .put("name_match_score", score);
        ((ObjectNode) entity.path("fund_account"))
                .put("id", text(validation.path("fund_account"), "id"))
                .set("vpa", validation.path("fund_account").path("vpa").deepCopy());
        root.put("created_at", clock.instant().getEpochSecond());
        return signed(root);
    }

    /** A signed payout event from the documented sample for it, carrying the payout as the fake holds it now. */
    public Signed event(String event, String payoutId) {
        var payout = Objects.requireNonNull(payouts.get(payoutId), "no payout " + payoutId);
        var root = sample("webhook-" + event.replace('.', '-'));
        var entity = (ObjectNode) root.path("payload").path("payout").path("entity");
        for (var field : List.of(
                "id",
                "fund_account_id",
                "amount",
                "currency",
                "status",
                "purpose",
                "utr",
                "mode",
                "reference_id",
                "narration",
                "status_details",
                "created_at")) {
            if (payout.has(field)) {
                entity.set(field, payout.get(field).deepCopy());
            }
        }
        root.put("created_at", clock.instant().getEpochSecond());
        return signed(root);
    }

    /** The same event signed with another secret, for the signature tests. */
    public static Signed resign(Signed signed, String secret) {
        return new Signed(
                signed.body(), hmac(secret, signed.body().getBytes(StandardCharsets.UTF_8)), signed.eventId());
    }

    public void reset() {
        validations.clear();
        payouts.clear();
        idempotency.clear();
        balance = 100_000_000L;
        autoSettle = false;
        clock = java.time.Clock.systemUTC();
    }

    private Signed signed(ObjectNode root) {
        var body = JSON.writeValueAsString(root);
        return new Signed(body, hmac(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8)), "evt_" + nextId());
    }

    private static String eventFor(String status) {
        return switch (status) {
            case "processing" -> "initiated";
            // RazorpayX documents no payout.cancelled event: a change to a payout is payout.updated.
            case "cancelled" -> "updated";
            default -> status;
        };
    }

    private static String sampleFor(String status) {
        return switch (status) {
            case "processing" -> "initiated";
            case "cancelled" -> "rejected";
            case "created" -> "queued";
            default -> status;
        };
    }

    private String nextId() {
        // RazorpayX's ids are 14 characters after the prefix; these are unique for the fake's life.
        return String.format("Fx%012d", sequence.incrementAndGet());
    }

    static ObjectNode sample(String name) {
        try (InputStream in = FakeRazorpayX.class.getResourceAsStream("/razorpayx/" + name + ".json")) {
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

    private static String localPart(String vpa) {
        int at = vpa.indexOf('@');
        return at < 0 ? vpa : vpa.substring(0, at);
    }

    /** Sends a signed event to the local stack's control plane, as RazorpayX would, after the delay. */
    void send(Signed signed) {
        var target = webhookTarget;
        if (target == null) {
            return;
        }
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
            System.err.println("fake-razorpayx: couldn't send a webhook to " + target + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The local stack's: a new payout goes on to the end its UPI ID steers it to, with each webhook on the way. */
    private void autoSettle(String payoutId, String vpa) {
        Thread.ofVirtual().start(() -> {
            var local = localPart(vpa);
            if (local.equals("payoutfails")) {
                send(settle(payoutId, "failed"));
                return;
            }
            send(event("payout.initiated", payoutId));
            send(settle(payoutId, "processed"));
            if (local.equals("reversed")) {
                send(settle(payoutId, "reversed"));
            }
        });
    }

    /** Every request to the fake, answered from its state. */
    private final class Behaviour implements ResponseDefinitionTransformerV2 {

        static final String NAME = "razorpayx";

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
                if (!authorised(request.getHeader("Authorization"))) {
                    return error(401, "Authentication failed");
                }
                if (method.equals("POST") && path.equals("/v1/fund_accounts/validations")) {
                    return validate(JSON.readTree(request.getBodyAsString()));
                }
                if (method.equals("GET") && path.startsWith("/v1/fund_accounts/validations/")) {
                    var found = validations.get(path.substring("/v1/fund_accounts/validations/".length()));
                    return found == null ? error(400, "The id provided does not exist") : json(200, fetched(found));
                }
                if (method.equals("POST") && path.equals("/v1/payouts")) {
                    return createPayout(request.getHeader("X-Payout-Idempotency"), request.getBodyAsString());
                }
                if (method.equals("GET") && path.equals("/v1/payouts")) {
                    if (!ACCOUNT_NUMBER.equals(query.get("account_number"))) {
                        return error(400, "The account number is invalid.");
                    }
                    return collection(payouts.values(), query);
                }
                if (method.equals("GET") && path.startsWith("/v1/payouts/")) {
                    var found = payouts.get(path.substring("/v1/payouts/".length()));
                    return found == null ? error(400, "The id provided does not exist") : json(200, found);
                }
                if (method.equals("GET") && path.equals("/v1/banking_balances")) {
                    var root = sample("api-balance-fetch");
                    var first = (ObjectNode) root.path("items").get(0);
                    first.put("account_number", ACCOUNT_NUMBER)
                            .put("amount", balance)
                            .put("available_amount", balance)
                            .put("refreshed_at", clock.instant().getEpochSecond());
                    var items = root.putArray("items");
                    items.add(first);
                    root.put("count", 1);
                    return json(200, root);
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

        private ResponseDefinition validate(JsonNode body) {
            if (!ACCOUNT_NUMBER.equals(body.path("source_account_number").asString())) {
                return error(400, "The source account number is invalid.");
            }
            var vpa = body.path("fund_account").path("vpa").path("address").asString();
            var name = body.path("fund_account").path("contact").path("name").asString();
            var local = localPart(vpa);
            var validation = sample("api-validation-vpa");
            var id = "fav_" + nextId();
            validation
                    .put("id", id)
                    .put("reference_id", body.path("reference_id").asString());
            var results = (ObjectNode) validation.path("validation_results");
            results.put("registered_name", name)
                    .put("account_status", local.equals("invalid") ? "invalid" : "active")
                    .put("name_match_score", local.equals("lowmatch") ? 42 : 100);
            if (local.equals("invalid")) {
                results.put("details", "The beneficiary account is invalid");
            }
            if (local.equals("slow")) {
                validation.put("status", "created");
            }
            var fundAccount = (ObjectNode) validation.path("fund_account");
            fundAccount.put("id", "fa_" + nextId());
            ((ObjectNode) fundAccount.path("vpa")).put("address", vpa);
            var contact = (ObjectNode) fundAccount.path("contact");
            contact.put("id", "cont_" + nextId())
                    .put("name", name)
                    .put(
                            "reference_id",
                            body.path("fund_account")
                                    .path("contact")
                                    .path("reference_id")
                                    .asString());
            validations.put(id, validation);
            return json(200, validation);
        }

        /** A check as the fetch call answers it: its results under {@code results}, as the documented sample has. */
        private ObjectNode fetched(ObjectNode validation) {
            var root = sample("api-validation-fetch");
            root.put("id", text(validation, "id")).put("status", text(validation, "status"));
            var results = validation.path("validation_results");
            ((ObjectNode) root.path("results"))
                    .put("account_status", text(results, "account_status"))
                    .put("registered_name", text(results, "registered_name"));
            ((ObjectNode) root.path("fund_account")).put("id", text(validation.path("fund_account"), "id"));
            return root;
        }

        private ResponseDefinition createPayout(@Nullable String key, String rawBody) {
            if (key == null || !key.matches("[A-Za-z0-9 _-]{4,36}")) {
                return error(400, "The X-Payout-Idempotency header is required.");
            }
            var earlier = idempotency.get(key);
            if (earlier != null) {
                return earlier[1].equals(rawBody)
                        ? json(200, payouts.get(earlier[0]))
                        : error(400, "Different request body sent for the same idempotency key");
            }
            var body = JSON.readTree(rawBody);
            if (!ACCOUNT_NUMBER.equals(body.path("account_number").asString())) {
                return error(400, "The account number is invalid.");
            }
            var fundAccountId = body.path("fund_account_id").asString();
            var vpa = validations.values().stream()
                    .filter(found -> fundAccountId.equals(text(found.path("fund_account"), "id")))
                    .map(found -> text(found.path("fund_account").path("vpa"), "address"))
                    .findFirst()
                    .orElse(null);
            if (vpa == null) {
                return error(400, "The fund account id provided does not exist");
            }
            long amount = body.path("amount").asLong();
            if (amount < 100) {
                return error(400, "The amount must be atleast INR 1.00");
            }
            var payout = sample("api-payout-create-vpa");
            var id = "pout_" + nextId();
            boolean covered = balance >= amount;
            payout.put("id", id)
                    .put("fund_account_id", fundAccountId)
                    .put("amount", amount)
                    .put("currency", body.path("currency").asString())
                    .put("mode", body.path("mode").asString())
                    .put("purpose", body.path("purpose").asString())
                    .put("reference_id", body.path("reference_id").asString())
                    .put("narration", body.path("narration").asString())
                    .put("created_at", clock.instant().getEpochSecond());
            payout.remove("notes");
            payouts.put(id, payout);
            if (covered) {
                balance -= amount;
                move(payout, "processing");
            } else {
                move(payout, "queued");
            }
            idempotency.put(key, new String[] {id, rawBody});
            if (autoSettle && covered) {
                autoSettle(id, vpa);
            }
            return json(200, payout);
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

        private ResponseDefinition json(int status, JsonNode body) {
            return aResponse()
                    .withStatus(status)
                    .withHeaders(new HttpHeaders(new HttpHeader("Content-Type", "application/json")))
                    .withBody(JSON.writeValueAsString(body))
                    .build();
        }

        /** An error in the documented shape ({@code api-error.json}), with our description. */
        private ResponseDefinition error(int status, String description) {
            var error = sample("api-error");
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
}
