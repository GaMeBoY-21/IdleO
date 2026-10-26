package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.CudaDrivers;
import com.idleo.controlplane.images.CheckedImages;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checks' queue, as the scanner works it (ADR 0042). A scanner claims the oldest queued check (or one whose claim
 * lapsed), with a claim of its own that every later step names; its progress keeps the claim. Every verdict is decided
 * here, from what the scanner reports: the platform, the size and the layers against their limits, a cached result for
 * the digest, the abuse blocklist against every ELF file's hash. A check whose scanner stopped reporting is claimed again,
 * and after {@code images.check-attempts} claims it's failed, not checked: never a pass.
 */
@Component
class ScannerQueue {

    private static final Logger log = LoggerFactory.getLogger(ScannerQueue.class);

    /** The refusals a scanner may report; each blocks the image. */
    static final Set<String> REFUSALS = Set.of("pull_failed", "unpack_failed", "too_large_unpacked", "unsafe_path");

    private final JdbcClient jdbc;
    private final ImageStore images;
    private final ScanStore scans;
    private final RegistryLogins logins;
    private final ImageJudge judge;
    private final ImageProperties properties;
    private final ImagesMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ScannerQueue(
            JdbcClient jdbc,
            ImageStore images,
            ScanStore scans,
            RegistryLogins logins,
            ImageJudge judge,
            ImageProperties properties,
            ImagesMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = jdbc;
        this.images = images;
        this.scans = scans;
        this.logins = logins;
        this.judge = judge;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** A check to do, for the scanner that claimed it. */
    record Claim(
            UUID checkId,
            String claim,
            String reference,
            CheckedImages.@Nullable Login login,
            String platform,
            long maxLayerUnpackedBytes,
            long maxImageUnpackedBytes,
            long deadlineSeconds) {}

    /** The claim was lost: the check is someone else's now, or over. */
    static final class ClaimLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ClaimLost() {
            super("the claim is no longer this scanner's", null, false, false);
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /** The oldest check waiting, claimed for a scanner, if there is one. */
    Optional<Claim> claim(String scanner) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var now = now();
            var id = jdbc.sql("""
                            select id from images.images
                            where state = 'queued'
                               or (state = 'checking' and claim_expires_at < :now and attempts < :attempts)
                            order by created_at, id
                            limit 1
                            for update skip locked
                            """)
                    .param("now", Timestamp.from(now))
                    .param("attempts", properties.checkAttempts())
                    .query(UUID.class)
                    .optional();
            if (id.isEmpty()) {
                return Optional.<Claim>empty();
            }
            var row = images.find(id.get()).orElseThrow();
            CheckedImages.Login login = null;
            if (row.isPrivate()) {
                var opened = row.loginId() == null ? Optional.<CheckedImages.Login>empty() : logins.open(row.loginId());
                if (opened.isEmpty()) {
                    images.finish(
                            row.id(),
                            ImageStore.State.FAILED,
                            null,
                            ImageVerdicts.NOT_CHECKED,
                            ImageVerdicts.notChecked(
                                    "the token for this private image is gone; check it again with one"),
                            now);
                    metrics.ended("failed");
                    return Optional.<Claim>empty();
                }
                login = opened.get();
            }
            var claim = UUID.randomUUID().toString();
            jdbc.sql("""
                            update images.images
                            set state = 'checking', claimed_by = :claim, claim_expires_at = :until, attempts = attempts + 1,
                                phase = 'downloading', done_bytes = 0, total_bytes = 0, updated_at = :now
                            where id = :id
                            """)
                    .param("claim", claim)
                    .param("until", Timestamp.from(now.plus(properties.claimLasts())))
                    .param("now", Timestamp.from(now))
                    .param("id", row.id())
                    .update();
            log.info("Scanner {} claimed image check {} (try {})", scanner, row.id(), row.attempts() + 1);
            return Optional.of(new Claim(
                    row.id(),
                    claim,
                    row.reference(),
                    login,
                    properties.platform(),
                    properties.maxLayerUnpacked().toBytes(),
                    properties.maxImageUnpacked().toBytes(),
                    properties.checkDeadline().toSeconds()));
        }));
    }

    /** The row, locked, if {@code claim} still holds it. */
    private ImageStore.Row claimed(UUID checkId, String claim) {
        return images.lock(checkId)
                .filter(row -> row.state() == ImageStore.State.CHECKING && claim.equals(row.claimedBy()))
                .orElseThrow(ClaimLost::new);
    }

    /**
     * What the reference resolved to. Answers whether to go on: not when the platform is missing, a limit is passed, or a
     * scan of the digest already answers the check.
     */
    boolean resolved(
            UUID checkId, String claim, @Nullable String digest, List<String> platforms, long compressed, int layers) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var row = claimed(checkId, claim);
            var now = now();
            if (digest == null) {
                block(row, null, "wrong_platform", ImageVerdicts.wrongPlatform(platforms, properties.platform()), now);
                return false;
            }
            if (compressed > properties.maxCompressed().toBytes()) {
                block(
                        row,
                        digest,
                        "too_large",
                        ImageVerdicts.tooLarge(
                                compressed, properties.maxCompressed().toBytes()),
                        now);
                return false;
            }
            if (layers > properties.maxLayers()) {
                block(row, digest, "too_many_layers", ImageVerdicts.tooManyLayers(layers, properties.maxLayers()), now);
                return false;
            }
            jdbc.sql(
                            "update images.images set digest = :digest, total_bytes = :total, updated_at = :now where id = :id")
                    .param("digest", digest)
                    .param("total", compressed)
                    .param("now", Timestamp.from(now))
                    .param("id", row.id())
                    .update();
            var cached = scans.find(digest);
            if (cached.isPresent()) {
                judge.settle(images.lock(row.id()).orElseThrow(), cached.get(), now);
                metrics.cached();
                return false;
            }
            return true;
        }));
    }

    /** The check's progress, which keeps its claim; false when the claim is lost. */
    boolean progress(UUID checkId, String claim, String phase, long done, long total) {
        var now = now();
        return images.progress(checkId, claim, phase, done, total, now, now.plus(properties.claimLasts()));
    }

    /** What the scanner found. */
    record Facts(
            String digest,
            long compressedBytes,
            int layers,
            @Nullable String cudaRequire,
            @Nullable String cudaVersion,
            List<String> entrypoint,
            List<String> cmd,
            @Nullable String workingDir,
            @Nullable String user,
            long unpackedBytes,
            boolean hasShell,
            List<ScanStore.Executable> executables,
            int critical,
            int high,
            int medium,
            int low,
            List<ScanStore.Vulnerability> vulnerabilities,
            List<ScanStore.Secret> secrets,
            String trivyVersion) {}

    /** The check's facts: kept for the digest, judged against the blocklist, and the check settled. */
    void result(UUID checkId, String claim, Facts facts) {
        transactions.executeWithoutResult(status -> {
            var row = claimed(checkId, claim);
            var now = now();
            if (!facts.digest().equals(row.digest())) {
                fail(row, "the scanner reported a different image than the one it resolved", now);
                return;
            }
            var scan = new ScanStore.Scan(
                    facts.digest(),
                    facts.compressedBytes(),
                    facts.unpackedBytes(),
                    facts.layers(),
                    facts.cudaRequire(),
                    facts.cudaVersion(),
                    CudaDrivers.needed(facts.cudaRequire(), facts.cudaVersion()),
                    facts.entrypoint(),
                    facts.cmd(),
                    facts.workingDir(),
                    facts.user(),
                    facts.hasShell(),
                    facts.critical(),
                    facts.high(),
                    facts.medium(),
                    facts.low(),
                    facts.secrets(),
                    facts.trivyVersion(),
                    now,
                    "",
                    null,
                    null,
                    null,
                    null);
            scans.insert(scan, facts.vulnerabilities(), facts.executables());
            judge.judgeNew(facts.digest(), now);
            judge.settle(
                    images.lock(row.id()).orElseThrow(),
                    scans.find(facts.digest()).orElseThrow(),
                    now);
        });
    }

    /** The scanner refused the image: it's blocked. */
    void refused(UUID checkId, String claim, String reason, String detail) {
        if (!REFUSALS.contains(reason)) {
            throw new IllegalArgumentException("not a refusal: " + reason);
        }
        transactions.executeWithoutResult(status -> block(claimed(checkId, claim), null, reason, detail, now()));
    }

    /** The scanner couldn't finish: not checked. */
    void failed(UUID checkId, String claim, String detail) {
        transactions.executeWithoutResult(status -> fail(claimed(checkId, claim), detail, now()));
    }

    private void block(ImageStore.Row row, @Nullable String digest, String reason, String detail, Instant now) {
        images.finish(row.id(), ImageStore.State.BLOCKED, digest, reason, detail, now);
        metrics.ended("blocked");
        log.info("Image check {} blocked: {}", row.id(), reason);
    }

    private void fail(ImageStore.Row row, String why, Instant now) {
        images.finish(
                row.id(), ImageStore.State.FAILED, null, ImageVerdicts.NOT_CHECKED, ImageVerdicts.notChecked(why), now);
        metrics.ended("failed");
        log.info("Image check {} failed: not checked", row.id());
    }

    /**
     * Checks whose scanners stopped reporting through every try: failed, not checked. Runs often; each instance takes rows
     * the others haven't locked.
     */
    @Scheduled(fixedDelayString = "${images.sweep-every}")
    void sweep() {
        try {
            transactions.executeWithoutResult(status -> {
                var now = now();
                var abandoned = jdbc.sql("""
                                select * from images.images
                                where state = 'checking' and claim_expires_at < :now and attempts >= :attempts
                                for update skip locked
                                """)
                        .param("now", Timestamp.from(now))
                        .param("attempts", properties.checkAttempts())
                        .query(ImageStore::row)
                        .list();
                for (var row : abandoned) {
                    fail(row, "the scanner stopped reporting " + row.attempts() + " times", now);
                }
            });
        } catch (RuntimeException e) {
            log.warn("The image checks' sweep failed; it runs again shortly", e);
        }
    }
}
