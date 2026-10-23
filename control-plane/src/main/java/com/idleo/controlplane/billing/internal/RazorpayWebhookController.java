package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay's webhooks (ADR 0039; https://razorpay.com/docs/webhooks/validate-test/), with no sign-in but Razorpay's
 * signature: {@code X-Razorpay-Signature} is the HMAC-SHA256 of the raw body under the webhook secret, checked before
 * the body is read at all, with the previous secret too until its grace period ends. Each event is handled once,
 * by {@code x-razorpay-event-id}; a repeat, or one that changes nothing, still answers 200, so Razorpay stops retrying.
 */
@RestController
class RazorpayWebhookController {

    /** Far above any event Razorpay documents. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final RazorpayProperties settings;
    private final PaymentEvents events;
    private final PaymentMetrics metrics;
    private final Clock clock;

    RazorpayWebhookController(RazorpayProperties settings, PaymentEvents events, PaymentMetrics metrics, Clock clock) {
        this.settings = settings;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @PostMapping("/v1/payments/razorpay/webhook")
    @ResponseStatus(HttpStatus.OK)
    void receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "A Razorpay event is at most 256 KiB.");
        }
        byte[] body;
        try (var in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "A Razorpay event is at most 256 KiB.");
        }
        if (!signed(body, request.getHeader("X-Razorpay-Signature"))) {
            metrics.signatureFailed("webhook");
            log.warn("Refused a Razorpay webhook whose signature doesn't check out");
            throw ApiProblem.of(Problem.UNAUTHENTICATED, "This call must carry Razorpay's signature.");
        }
        try {
            events.handle(request.getHeader("x-razorpay-event-id"), body);
        } catch (PaymentEvents.Unreadable unreadable) {
            throw ApiProblem.of(Problem.MALFORMED_REQUEST, "The body isn't a Razorpay event.");
        }
    }

    /** Under the webhook secret, or the one before it while its grace period lasts. */
    boolean signed(byte[] body, String signature) {
        var current = settings.webhookSecret();
        if (current != null
                && !current.isBlank()
                && RazorpaySignatures.webhookSignatureValid(body, signature, current)) {
            return true;
        }
        var previous = settings.previousWebhookSecret();
        var until = settings.previousWebhookSecretUntil();
        return previous != null
                && !previous.isBlank()
                && until != null
                && clock.instant().isBefore(until)
                && RazorpaySignatures.webhookSignatureValid(body, signature, previous);
    }
}
