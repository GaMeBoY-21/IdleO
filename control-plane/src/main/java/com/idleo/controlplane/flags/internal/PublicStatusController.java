package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.Flag;
import com.idleo.controlplane.Flags;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What's paused, for the app's banner and the website's status line (ADR 0056), with no sign-in and limited per address
 * like every signed-out call. Never an admin's reason: only what the flag itself says to everyone.
 */
@RestController
class PublicStatusController {

    private static final CacheControl TEN_SECONDS =
            CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic();

    private final Flags flags;

    PublicStatusController(Flags flags) {
        this.flags = flags;
    }

    /**
     * One paused part.
     *
     * @param flag its key, such as {@code bookings.new}
     * @param message what to tell people, such as "Bookings are paused: …"
     * @param until when it's expected back, if known
     */
    record Paused(String flag, String message, @Nullable Instant until) {}

    /**
     * @param maintenance the app is read-only now
     * @param paused everything paused, maintenance included, in the flags' order
     */
    record PublicStatus(boolean maintenance, List<Paused> paused) {}

    @GetMapping("/v1/public/status")
    ResponseEntity<PublicStatus> status() {
        var paused = Arrays.stream(Flag.values())
                .flatMap(flag -> flags.pause(flag).stream())
                .map(pause -> new Paused(pause.flag().key(), pause.message(), pause.until()))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(TEN_SECONDS)
                .body(new PublicStatus(flags.paused(Flag.MAINTENANCE), paused));
    }
}
