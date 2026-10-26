package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.Sealer;
import java.security.SecureRandom;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Seals PANs for the database (ADR 0040), as the admin console's TOTP secrets are (ADR 0027): the shared kernel's {@link
 * Sealer} under {@code payouts.pan.key}, with the person's id as associated data, so a sealed PAN copied to another
 * person's row doesn't open. Without a key (a command-line run that never touches a PAN), it refuses only when it's used;
 * the prod profile refuses to start without one.
 */
final class PanSeal {

    private final Sealer sealer;

    PanSeal(@Nullable String configured, SecureRandom random) {
        this.sealer = new Sealer(configured, "payouts.pan.key (PAYOUTS_PAN_KEY)", random);
    }

    byte[] seal(UUID userId, String pan) {
        // A PAN is ASCII, so its UTF-8 bytes are the ASCII bytes it was always sealed as.
        return sealer.seal(userId.toString(), pan);
    }

    String open(UUID userId, byte[] stored) {
        return sealer.open(userId.toString(), stored);
    }
}
