package com.idleo.controlplane.scheduling.internal;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pricing page's numbers (ADR 0029): {@code GET /v1/public/availability}, with no sign-in. It is limited per
 * address like every other call without one, cached for a minute in the server and in {@code Cache-Control}, and
 * never has a host's identity or a count of hosts in it.
 */
@RestController
@RequestMapping("/v1/public/availability")
class PublicAvailabilityController {

    private final PublicAvailability availability;

    PublicAvailabilityController(PublicAvailability availability) {
        this.availability = availability;
    }

    @GetMapping
    ResponseEntity<PublicAvailability.PublicAvailabilityAnswer> get() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(availability.get());
    }
}
