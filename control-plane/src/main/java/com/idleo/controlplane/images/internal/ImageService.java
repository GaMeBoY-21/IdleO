package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.ApiProblem.FieldError;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.billing.VerifiedPans;
import com.idleo.controlplane.images.CheckedImages;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A renter's own images (ADR 0042): submitting one for its check, reading the renter's own, removing one, and what
 * sessions books, offers and starts with. Only a renter with a verified PAN may submit or book one; another person's is
 * {@code not-found}.
 */
@Service
class ImageService implements CheckedImages {

    /** Why a renter can't use their own image, said to them; null when they can. */
    static final String WHY_NOT = "Your own images are for renters with a verified PAN. Give yours in Payout details "
            + "(Hosting, Payout details); once it's verified you can check and run your own image.";

    private final ImageStore images;
    private final ScanStore scans;
    private final RegistryLogins logins;
    private final VerifiedPans pans;
    private final ImageProperties properties;
    private final ImagesMetrics metrics;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImageService(
            ImageStore images,
            ScanStore scans,
            RegistryLogins logins,
            VerifiedPans pans,
            ImageProperties properties,
            ImagesMetrics metrics,
            IdGenerator ids,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.images = images;
        this.scans = scans;
        this.logins = logins;
        this.pans = pans;
        this.properties = properties;
        this.metrics = metrics;
        this.ids = ids;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Whether the renter may use their own images now. */
    boolean allowed(UUID renterId) {
        return pans.verified(renterId);
    }

    private void requireAllowed(UUID renterId) {
        if (!allowed(renterId)) {
            throw ApiProblem.of(Problem.VERIFIED_PAN_REQUIRED, WHY_NOT);
        }
    }

    /**
     * A reference to check, with a private registry's user name and read-only token (both or neither).
     *
     * @param username the registry's user name, for a private image
     * @param token its read-only token, never logged or shown back
     */
    record Submission(
            String reference,
            @Nullable String username,
            @Nullable String token) {

        @Override
        public String toString() {
            return "Submission[reference=" + reference + ", username=" + username + ", token=(never printed)]";
        }
    }

    /** Queues a check of the reference; the renter follows it at {@code GET /v1/images/{id}}. */
    ImageStore.Row submit(UUID renterId, Submission submission) {
        requireAllowed(renterId);
        ImageReference reference;
        try {
            reference = ImageReference.parse(submission.reference(), properties.devRegistries());
        } catch (ImageReference.Invalid invalid) {
            throw ApiProblem.invalid("reference", invalid.getMessage());
        }
        var username = blankToNull(submission.username());
        var token = blankToNull(submission.token());
        if ((username == null) != (token == null)) {
            var missing = username == null ? "registryUsername" : "registryToken";
            throw ApiProblem.invalid(
                    "A private registry needs both its user name and a read-only token.",
                    List.of(new FieldError(missing, "is needed with the other.")));
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var now = Instant.now(clock);
            var loginId = token == null
                    ? null
                    : logins.keep(renterId, reference.registry(), Objects.requireNonNull(username), token);
            var row = new ImageStore.Row(
                    ids.newId(),
                    renterId,
                    reference.canonical(),
                    reference.registry(),
                    reference.repository(),
                    loginId,
                    token != null,
                    null,
                    ImageStore.State.QUEUED,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    0,
                    0,
                    now,
                    now,
                    null,
                    null);
            images.insert(row);
            metrics.submitted();
            return images.find(row.id()).orElseThrow();
        }));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    List<ImageStore.Row> mine(UUID renterId) {
        return images.ofUser(renterId);
    }

    /** The renter's own image; another person's is {@code not-found}. */
    ImageStore.Row own(UUID renterId, UUID imageId) {
        return images.find(imageId)
                .filter(row -> row.userId().equals(renterId))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such image."));
    }

    Optional<ScanStore.Scan> scanOf(ImageStore.Row row) {
        return row.digest() == null || row.state() == ImageStore.State.FAILED
                ? Optional.empty()
                : scans.find(row.digest());
    }

    List<ScanStore.Vulnerability> vulnerabilities(UUID renterId, UUID imageId) {
        var row = own(renterId, imageId);
        return scanOf(row).map(scan -> scans.vulnerabilities(scan.digest())).orElse(List.of());
    }

    /**
     * Removes the renter's image: its record, and its token if no other image uses it. Sessions already booked keep the
     * reference they were booked with; a check under way is dropped (the scanner's next step finds its claim gone).
     */
    void remove(UUID renterId, UUID imageId) {
        transactions.executeWithoutResult(status -> {
            var row = own(renterId, imageId);
            images.delete(row.id());
            if (row.loginId() != null) {
                logins.forgetUnused(row.loginId());
            }
        });
    }

    @Override
    public Image forBooking(UUID renterId, UUID imageId) {
        requireAllowed(renterId);
        var row = images.find(imageId)
                .filter(found -> found.userId().equals(renterId))
                .orElseThrow(() -> ApiProblem.of(
                        Problem.NOT_FOUND,
                        "No such image.",
                        List.of(new FieldError("imageId", "isn't one of yours."))));
        var field = List.of(new FieldError("imageId", "can't be booked yet."));
        switch (row.state()) {
            case QUEUED, CHECKING ->
                throw ApiProblem.of(
                        Problem.IMAGE_NOT_READY,
                        "Your image is still being checked; book it once the check is done.",
                        field);
            case FAILED -> throw ApiProblem.of(Problem.IMAGE_NOT_READY, Objects.requireNonNull(row.detail()), field);
            case BLOCKED ->
                throw ApiProblem.of(
                        Problem.IMAGE_BLOCKED,
                        Objects.requireNonNull(row.detail()),
                        List.of(new FieldError("imageId", "is blocked.")));
            case READY -> {}
        }
        if (row.isPrivate() && row.loginId() == null) {
            throw ApiProblem.of(
                    Problem.IMAGE_NOT_READY,
                    "The token for this private image was deleted after 30 days unused. Check the image again with a "
                            + "read-only token.",
                    field);
        }
        return runnable(row)
                .orElseThrow(() -> ApiProblem.of(
                        Problem.IMAGE_BLOCKED,
                        ImageVerdicts.BLOCKLISTED,
                        List.of(new FieldError("imageId", "is blocked."))));
    }

    @Override
    public Optional<Image> runnable(UUID imageId) {
        return images.find(imageId).flatMap(this::runnable);
    }

    private Optional<Image> runnable(ImageStore.Row row) {
        if (row.state() != ImageStore.State.READY || row.digest() == null) {
            return Optional.empty();
        }
        var digest = row.digest();
        return scans.find(digest)
                .filter(scan -> !scan.blocked() && scan.judged())
                .map(scan -> new Image(
                        row.id(),
                        row.userId(),
                        row.registry() + "/" + row.repository() + "@" + digest,
                        digest,
                        scan.cudaNeeded(),
                        scan.entrypoint(),
                        scan.cmd(),
                        scan.workingDir(),
                        scan.hasShell(),
                        scan.compressedBytes(),
                        scan.unpackedBytes()));
    }

    @Override
    public void used(UUID imageId) {
        images.used(imageId, Instant.now(clock));
    }

    @Override
    public java.time.Duration pullTimeLimit() {
        return properties.pullTimeLimit();
    }

    @Override
    public Optional<Login> pullLogin(UUID imageId) {
        return images.find(imageId)
                .filter(ImageStore.Row::isPrivate)
                .flatMap(row -> Optional.ofNullable(row.loginId()))
                .flatMap(logins::open);
    }
}
