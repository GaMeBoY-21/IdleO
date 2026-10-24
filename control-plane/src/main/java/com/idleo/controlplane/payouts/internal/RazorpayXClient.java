package com.idleo.controlplane.payouts.internal;

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
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The calls we make to RazorpayX (ADR 0040), each with only the fields its documentation lists:
 *
 * <ul>
 *   <li>check a UPI ID (https://razorpay.com/docs/api/x/account-validation/vpa/), which makes its contact and fund
 *       account in the same call, and fetch a check (…/api/x/account-validation/fetch-transactions-with-id/);
 *   <li>pay a fund account by UPI (…/api/x/payouts/create/vpa/), with {@code X-Payout-Idempotency}
 *       (…/api/x/payout-idempotency/make-request/), fetch a payout (…/api/x/payouts/fetch-with-id/) and list a period's
 *       (…/api/x/payouts/fetch-all/);
 *   <li>the business account's balance (…/api/x/account-validation/balance-fetch/).
 * </ul>
 *
 * HTTP basic authentication with the key id and secret; amounts in paise. A call RazorpayX refuses throws {@link
 * Refused} with its error's code, reason and description; one that can't be made throws {@link Unreachable}.
 */
@Component
class RazorpayXClient {

    /** The most a list call returns at once (the documentation's maximum). */
    static final int PAGE = 100;

    /** What the host's bank statement says (at most 30 letters, digits and spaces). */
    static final String NARRATION = "IdleO earnings";

    private final RazorpayXProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    RazorpayXClient(RazorpayXProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    /**
     * A UPI ID's check. {@code status} is created (no answer yet), completed or failed; {@code accountStatus} active or
     * invalid once completed; the score is how well the registered name matches the one we sent, 0 to 100.
     */
    record Validation(
            String id,
            String status,
            @Nullable String accountStatus,
            @Nullable String registeredName,
            @Nullable Integer nameMatchScore,
            @Nullable String fundAccountId,
            @Nullable String vpa) {}

    /**
     * A payout. {@code statusReason}, {@code statusSource} and {@code statusDescription} are its {@code status_details}
     * (https://razorpay.com/docs/errors/x/payout-status-details/).
     */
    record Payout(
            String id,
            long amount,
            String currency,
            String status,
            @Nullable String referenceId,
            @Nullable String fundAccountId,
            @Nullable String utr,
            @Nullable String statusReason,
            @Nullable String statusSource,
            @Nullable String statusDescription,
            Instant createdAt) {}

    /** RazorpayX answered with an error. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final int status;
        final @Nullable String code;
        final @Nullable String reason;

        Refused(int status, @Nullable String code, @Nullable String reason, String description) {
            super(description);
            this.status = status;
            this.code = code;
            this.reason = reason;
        }
    }

    /** RazorpayX couldn't be reached, or answered with something that isn't its API's. */
    static final class Unreachable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreachable(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    boolean enabled() {
        return properties.enabled();
    }

    /**
     * Checks a UPI ID: RazorpayX makes the contact (named as on the PAN, ours by {@code contactReference}) and its fund
     * account, and asks the bank who it belongs to. {@code reference} is ours for the check.
     */
    Validation validateVpa(String vpa, String name, String contactReference, String reference) {
        var contact = new LinkedHashMap<String, Object>();
        contact.put("name", name);
        contact.put("type", "vendor");
        contact.put("reference_id", contactReference);
        var fundAccount = new LinkedHashMap<String, Object>();
        fundAccount.put("account_type", "vpa");
        fundAccount.put("vpa", Map.of("address", vpa));
        fundAccount.put("contact", contact);
        var body = new LinkedHashMap<String, Object>();
        body.put("source_account_number", properties.accountNumber());
        body.put("validation_type", "pennydrop");
        body.put("reference_id", reference);
        body.put("fund_account", fundAccount);
        return validation(call("POST", "/v1/fund_accounts/validations", body, null));
    }

    Validation fetchValidation(String id) {
        return validation(call("GET", "/v1/fund_accounts/validations/" + encode(id), null, null));
    }

    /**
     * Pays {@code amount} paise to the fund account by UPI. The idempotency key and the reference are both our payout's
     * id, so sending it again pays nothing twice; queued if the balance is low.
     */
    Payout createPayout(String payoutId, String fundAccountId, long amount) {
        var body = new LinkedHashMap<String, Object>();
        body.put("account_number", properties.accountNumber());
        body.put("fund_account_id", fundAccountId);
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("mode", "UPI");
        body.put("purpose", "payout");
        body.put("queue_if_low_balance", true);
        body.put("reference_id", payoutId);
        body.put("narration", NARRATION);
        return payout(call("POST", "/v1/payouts", body, payoutId));
    }

    Payout fetchPayout(String id) {
        return payout(call("GET", "/v1/payouts/" + encode(id), null, null));
    }

    /** Every payout from our account created from {@code from} to {@code to}, a page of 100 at a time. */
    List<Payout> payouts(Instant from, Instant to) {
        var all = new ArrayList<Payout>();
        for (int skip = 0; ; skip += PAGE) {
            var page = call(
                    "GET",
                    "/v1/payouts?account_number=" + encode(Objects.requireNonNull(properties.accountNumber()))
                            + "&from=" + from.getEpochSecond() + "&to=" + to.getEpochSecond() + "&count=" + PAGE
                            + "&skip=" + skip,
                    null,
                    null);
            var items = page.path("items");
            items.forEach(item -> all.add(payout(item)));
            if (items.size() < PAGE) {
                return all;
            }
        }
    }

    /** What our business account can pay out now ({@code available_amount}), if RazorpayX lists it. */
    Optional<Long> availableBalance() {
        for (int skip = 0; ; skip += PAGE) {
            var items = call("GET", "/v1/banking_balances?count=" + PAGE + "&skip=" + skip, null, null)
                    .path("items");
            for (var item : items) {
                if (Objects.equals(optional(item, "account_number"), properties.accountNumber())) {
                    return Optional.of(item.path("available_amount").asLong());
                }
            }
            if (items.size() < PAGE) {
                return Optional.empty();
            }
        }
    }

    Payout payout(JsonNode node) {
        var details = node.path("status_details");
        var created = node.path("created_at");
        return new Payout(
                text(node, "id"),
                node.path("amount").asLong(),
                text(node, "currency"),
                text(node, "status"),
                optional(node, "reference_id"),
                optional(node, "fund_account_id"),
                optional(node, "utr"),
                details.isObject() ? optional(details, "reason") : null,
                details.isObject() ? optional(details, "source") : null,
                details.isObject() ? optional(details, "description") : null,
                created.isNumber() ? Instant.ofEpochSecond(created.asLong()) : Instant.EPOCH);
    }

    /**
     * A check, as the create call answers it ({@code validation_results}) or as the fetch call and the webhooks do
     * ({@code results}): the documentation shows both.
     */
    Validation validation(JsonNode node) {
        var results =
                node.path("validation_results").isObject() ? node.path("validation_results") : node.path("results");
        var fundAccount = node.path("fund_account");
        var score = results.path("name_match_score");
        Integer matchScore = null;
        if (score.isNumber()) {
            matchScore = score.asInt();
        } else if (score.isString() && score.asString().matches("[0-9]{1,3}")) {
            matchScore = Integer.parseInt(score.asString());
        }
        return new Validation(
                text(node, "id"),
                text(node, "status"),
                results.isObject() ? optional(results, "account_status") : null,
                results.isObject() ? optional(results, "registered_name") : null,
                matchScore,
                fundAccount.isObject() ? optional(fundAccount, "id") : null,
                fundAccount.path("vpa").isObject() ? optional(fundAccount.path("vpa"), "address") : null);
    }

    private JsonNode call(String method, String path, @Nullable Object body, @Nullable String idempotencyKey) {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                    "RazorpayX isn't configured: RAZORPAYX_KEY_ID, RAZORPAYX_KEY_SECRET and RAZORPAYX_ACCOUNT_NUMBER");
        }
        var credentials = Base64.getEncoder()
                .encodeToString((properties.keyId() + ":" + properties.keySecret()).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(
                        URI.create(properties.apiUrl().toString().replaceAll("/+$", "") + path))
                .timeout(properties.timeout())
                .header("Authorization", "Basic " + credentials)
                .header("Accept", "application/json");
        if (idempotencyKey != null) {
            request.header("X-Payout-Idempotency", idempotencyKey);
        }
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
            throw new Unreachable("RazorpayX couldn't be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("interrupted while calling RazorpayX", e);
        }
        JsonNode node;
        try {
            node = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new Unreachable("RazorpayX answered " + response.statusCode() + " with something that isn't JSON", e);
        }
        if (response.statusCode() >= 500) {
            throw new Unreachable("RazorpayX answered " + response.statusCode(), null);
        }
        if (response.statusCode() >= 400) {
            var error = node.path("error");
            throw new Refused(
                    response.statusCode(),
                    optional(error, "code"),
                    optional(error, "reason"),
                    Objects.requireNonNullElse(optional(error, "description"), "RazorpayX refused the request"));
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        var value = optional(node, field);
        if (value == null) {
            throw new Unreachable("RazorpayX's answer has no " + field, null);
        }
        return value;
    }

    static @Nullable String optional(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asString().isEmpty() ? null : value.asString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
