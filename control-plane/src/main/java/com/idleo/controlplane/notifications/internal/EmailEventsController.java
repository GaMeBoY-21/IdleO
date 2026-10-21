package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The email provider's bounces and complaints (ADR 0036, docs/runbooks/email.md): {@code POST
 * /v1/email-events/resend}, with no sign-in but the provider's signature, checked before the body is read as an event;
 * an unsigned or badly signed call is a 401 and changes nothing. A hard bounce stops email to its address and the app
 * asks the person to check it; a complaint stops the email they can switch off. The provider retries anything but a
 * 2xx, and its event id makes a repeat change nothing. Not part of the public API, so it's left out of the contract.
 */
@Hidden
@RestController
class EmailEventsController {

    private static final Logger log = LoggerFactory.getLogger(EmailEventsController.class);
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final EmailEvents events;
    private final EmailSuppressions suppressions;
    private final MeterRegistry registry;

    EmailEventsController(EmailEvents events, EmailSuppressions suppressions, MeterRegistry registry) {
        this.events = events;
        this.suppressions = suppressions;
        this.registry = registry;
    }

    @PostMapping("/v1/email-events/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resend(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "An email event is at most 64 KiB.");
        }
        byte[] body;
        try (var in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "An email event is at most 64 KiB.");
        }
        var headers = new HashMap<String, String>();
        for (var name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        EmailEvents.Event event;
        try {
            event = events.read(headers, body);
        } catch (EmailEvents.Unsigned unsigned) {
            registry.counter("notifications.email-events", "outcome", "refused").increment();
            log.warn("Refused an email event: {}", unsigned.getMessage());
            throw ApiProblem.of(Problem.UNAUTHENTICATED, "This call must carry the email provider's signature.");
        } catch (IllegalArgumentException unreadable) {
            throw ApiProblem.of(Problem.MALFORMED_REQUEST, "The body isn't an email event.");
        }
        var reason = switch (event.kind()) {
            case HARD_BOUNCE -> EmailSuppressions.Reason.BOUNCED;
            case COMPLAINT -> EmailSuppressions.Reason.COMPLAINED;
            case SOFT_BOUNCE, OTHER -> null;
        };
        registry.counter("notifications.emails", "outcome", outcome(event.kind()))
                .increment();
        if (reason == null) {
            return;
        }
        for (var address : event.addresses()) {
            suppressions.record(address, reason, event.id());
        }
        // Never the address: it's the person's.
        log.info(
                "Email event {}: {} for {} address(es)",
                event.id(),
                event.kind(),
                event.addresses().size());
    }

    private static String outcome(EmailEvents.Kind kind) {
        return switch (kind) {
            case HARD_BOUNCE -> "bounced";
            case SOFT_BOUNCE -> "soft_bounced";
            case COMPLAINT -> "complained";
            case OTHER -> "other_event";
        };
    }
}
