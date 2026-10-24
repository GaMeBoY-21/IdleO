package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RazorpayWebhookSignature;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RazorpayX's webhooks (ADR 0040; https://razorpay.com/docs/webhooks/payouts/), with no sign-in but its signature:
 * {@code X-Razorpay-Signature}, the HMAC-SHA256 of the raw body under {@code RAZORPAYX_WEBHOOK_SECRET}, checked before
 * the body is read. Each event is handled once by {@code x-razorpay-event-id}; a repeat still answers 200, so RazorpayX
 * stops retrying.
 */
@RestController
class RazorpayXWebhookController {

    /** Far above any event RazorpayX documents. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(RazorpayXWebhookController.class);

    private final RazorpayXProperties settings;
    private final PayoutEvents events;
    private final PayoutsMetrics metrics;

    RazorpayXWebhookController(RazorpayXProperties settings, PayoutEvents events, PayoutsMetrics metrics) {
        this.settings = settings;
        this.events = events;
        this.metrics = metrics;
    }

    @PostMapping("/v1/payouts/razorpayx/webhook")
    @ResponseStatus(HttpStatus.OK)
    void receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "A RazorpayX event is at most 256 KiB.");
        }
        byte[] body;
        try (var in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "A RazorpayX event is at most 256 KiB.");
        }
        var secret = settings.webhookSecret();
        if (secret == null
                || secret.isBlank()
                || !RazorpayWebhookSignature.valid(body, request.getHeader("X-Razorpay-Signature"), secret)) {
            metrics.signatureFailed();
            log.warn("Refused a RazorpayX webhook whose signature doesn't check out");
            throw ApiProblem.of(Problem.UNAUTHENTICATED, "This call must carry RazorpayX's signature.");
        }
        try {
            events.handle(request.getHeader("x-razorpay-event-id"), body);
        } catch (PayoutEvents.Unreadable unreadable) {
            throw ApiProblem.of(Problem.MALFORMED_REQUEST, "The body isn't a RazorpayX event.");
        }
    }
}
