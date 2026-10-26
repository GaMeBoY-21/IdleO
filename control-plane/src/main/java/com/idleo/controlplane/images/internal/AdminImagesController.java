package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renters' own images, for the admin console (ADR 0042): every submission by state, newest first; one with what its check
 * found (and, when the blocklist blocked it, which file); and a manual block of its digest for everyone who has it, or
 * lifting one an admin made. Each block and unblock is audited with the admin's reason, in the same transaction.
 */
@RestController
@RequestMapping("/admin/v1/images")
@ConditionalOnWebApplication
class AdminImagesController {

    private final JdbcClient jdbc;
    private final ImageStore images;
    private final ScanStore scans;
    private final ImageJudge judge;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    AdminImagesController(
            JdbcClient jdbc,
            ImageStore images,
            ScanStore scans,
            ImageJudge judge,
            AuditTrail audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = jdbc;
        this.images = images;
        this.scans = scans;
        this.judge = judge;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** A submission, as the list shows it. */
    record AdminImage(
            UUID id,
            UUID userId,
            String reference,
            boolean privateRegistry,

            @Schema(allowableValues = {"queued", "checking", "ready", "blocked", "failed"})
            String state,

            @Nullable String reason,
            @Nullable String digest,
            @Nullable Integer critical,
            @Nullable Integer high,
            int secrets,
            @Nullable Long compressedBytes,
            Instant createdAt,
            @Nullable Instant checkedAt) {}

    record AdminImagePage(List<AdminImage> items, @Nullable String nextCursor) {}

    /**
     * One submission with its findings.
     *
     * @param blockedFile the listed program's path and layer, when the blocklist blocked it
     * @param blockedBy {@code blocklisted} or {@code admin}, when its digest is blocked
     * @param blocklistVersion the abuse blocklist it was last judged by
     * @param submissions how many submissions of any renter have this digest
     */
    record AdminImageDetail(
            AdminImage image,
            @Nullable String detail,
            @Nullable String blockedBy,
            @Nullable String blockedFile,
            @Nullable String blocklistVersion,
            @Nullable String cudaNeeded,
            int executables,
            int submissions,
            List<ImageController.ImageWarning> warnings) {}

    @GetMapping
    AdminImagePage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        if (state != null
                && !List.of("queued", "checking", "ready", "blocked", "failed").contains(state)) {
            throw ApiProblem.invalid("state", "must be queued, checking, ready, blocked or failed.");
        }
        int size = AdminPages.limit(limit);
        var after = AdminPages.after(cursor);
        var rows = jdbc.sql("""
                        select * from images.images
                        where (cast(:state as text) is null or state = :state)
                          and (cast(:after as uuid) is null or id < :after)
                        order by id desc
                        limit :limit
                        """)
                .param("state", state)
                .param("after", after)
                .param("limit", size + 1)
                .query(ImageStore::row)
                .list();
        var page = rows.stream().limit(size).map(this::summary).toList();
        return new AdminImagePage(
                page, rows.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    AdminImageDetail get(@PathVariable UUID id) {
        var row = images.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such image."));
        var scan = row.digest() == null ? null : scans.find(row.digest()).orElse(null);
        int executables = scan == null
                ? 0
                : jdbc.sql("select count(*) from images.scan_executables where digest = :d")
                        .param("d", scan.digest())
                        .query(Integer.class)
                        .single();
        int submissions = row.digest() == null
                ? 1
                : jdbc.sql("select count(*) from images.images where digest = :d")
                        .param("d", row.digest())
                        .query(Integer.class)
                        .single();
        return new AdminImageDetail(
                summary(row),
                row.detail(),
                scan == null ? null : scan.blockedReason(),
                scan == null ? null : scan.blockedPath(),
                scan == null ? null : scan.blocklistVersion(),
                scan == null ? null : scan.cudaNeeded(),
                executables,
                submissions,
                scan == null
                        ? List.of()
                        : ImageVerdicts.warnings(scan).stream()
                                .map(w -> new ImageController.ImageWarning(w.code(), w.message()))
                                .toList());
    }

    /**
     * Blocks the submission's digest for every renter who has it, with the admin's reason: no new session of it starts,
     * those not started are cancelled, and each owner is told. 409 {@code action-refused} before its check has found a
     * digest, or when the digest is blocked already.
     */
    @PostMapping("/{id}/block")
    AdminImageDetail block(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        transactions.executeWithoutResult(status -> {
            var row = images.lock(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such image."));
            var digest = row.digest();
            var scan = digest == null ? null : scans.find(digest).orElse(null);
            if (digest == null || scan == null) {
                throw ApiProblem.of(
                        Problem.ACTION_REFUSED,
                        "This image's check hasn't found what it is yet, so there's nothing to block.");
            }
            if (scan.blocked()) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "This image is blocked already.");
            }
            judge.block(digest, "admin", ImageVerdicts.ADMIN, null, Instant.now(clock));
            audit.record(new AuditTrail.Entry(
                    audit.admin(admin),
                    "image.block",
                    "image",
                    id.toString(),
                    request.reason(),
                    null,
                    Map.of("digest", digest)));
        });
        return get(id);
    }

    /** Lifts an admin's block of the submission's digest, with the admin's reason. 409 unless an admin blocked it. */
    @PostMapping("/{id}/unblock")
    AdminImageDetail unblock(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        transactions.executeWithoutResult(status -> {
            var row = images.lock(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such image."));
            var scan = row.digest() == null ? null : scans.find(row.digest()).orElse(null);
            if (scan == null || !"admin".equals(scan.blockedReason())) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "Only an admin's block can be lifted here.");
            }
            judge.unblock(Objects.requireNonNull(row.digest()), Instant.now(clock));
            audit.record(new AuditTrail.Entry(
                    audit.admin(admin),
                    "image.unblock",
                    "image",
                    id.toString(),
                    request.reason(),
                    Map.of("digest", scan.digest()),
                    null));
        });
        return get(id);
    }

    private AdminImage summary(ImageStore.Row row) {
        var scan = row.digest() == null ? null : scans.find(row.digest()).orElse(null);
        return new AdminImage(
                row.id(),
                row.userId(),
                row.reference(),
                row.isPrivate(),
                row.state().db(),
                row.reason(),
                row.digest(),
                scan == null ? null : scan.critical(),
                scan == null ? null : scan.high(),
                scan == null ? 0 : scan.secrets().size(),
                scan == null ? null : scan.compressedBytes(),
                row.createdAt(),
                row.checkedAt());
    }
}
