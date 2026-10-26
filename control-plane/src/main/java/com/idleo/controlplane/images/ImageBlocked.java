package com.idleo.controlplane.images;

import java.util.List;
import java.util.UUID;

/**
 * A checked image was blocked after it was ready (ADR 0042): a newer abuse blocklist names a program in it, or an admin
 * blocked it. Every renter's submission of that digest is blocked at once. Sessions cancels the ones that haven't started;
 * notifications tells each owner.
 *
 * @param images the submissions now blocked, each with its owner and reference
 * @param reason {@code blocklisted} or {@code admin}
 */
public record ImageBlocked(String digest, List<Blocked> images, String reason) {

    public ImageBlocked {
        images = List.copyOf(images);
    }

    /** One renter's submission of the image. */
    public record Blocked(UUID imageId, UUID ownerId, String reference) {}
}
