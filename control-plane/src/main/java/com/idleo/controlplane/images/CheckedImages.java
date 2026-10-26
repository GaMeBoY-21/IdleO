package com.idleo.controlplane.images;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What sessions needs of a renter's own image (ADR 0042): to book it, to offer and start it, and to pull it. */
public interface CheckedImages {

    /**
     * A checked image, as a session runs it.
     *
     * @param reference the reference pinned by digest, {@code registry/repository@sha256:…}: the only thing that runs
     * @param cudaNeeded the CUDA version it needs ({@code 12.4}), or null when it says none
     * @param entrypoint its own entrypoint
     * @param cmd its own command
     * @param workingDir its own working directory, or null for none
     * @param hasShell whether it has a {@code /bin/sh}, which a command the renter types runs with
     */
    record Image(
            UUID id,
            UUID ownerId,
            String reference,
            String digest,
            @Nullable String cudaNeeded,
            List<String> entrypoint,
            List<String> cmd,
            @Nullable String workingDir,
            boolean hasShell,
            long compressedBytes,
            long unpackedBytes) {

        public Image {
            entrypoint = List.copyOf(entrypoint);
            cmd = List.copyOf(cmd);
        }
    }

    /**
     * The renter's image, if they may book it now: the renter still has a verified PAN, the image is theirs ({@code
     * not-found} otherwise), its check is complete ({@code image-not-ready}), it isn't blocked ({@code image-blocked}), and
     * a private one still has its token. Every refusal is an {@code ApiProblem} naming the field {@code imageId}.
     */
    Image forBooking(UUID renterId, UUID imageId);

    /** The image, while it isn't blocked: what a session of it offers and starts with. */
    Optional<Image> runnable(UUID imageId);

    /** A booking used it: it's kept {@code retention.periods.images} from now. */
    void used(UUID imageId);

    /** A private image's user name and token, opened for one StartSession; marks the token used. Never stored. */
    record Login(String username, String token) {

        @Override
        public String toString() {
            return "Login[username=" + username + ", token=(never printed)]";
        }
    }

    Optional<Login> pullLogin(UUID imageId);

    /** How long a host may take to download an image at a session's start ({@code images.pull-time-limit}). */
    java.time.Duration pullTimeLimit();
}
