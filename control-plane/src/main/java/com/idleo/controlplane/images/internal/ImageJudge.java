package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.hosts.Blocklists;
import com.idleo.controlplane.images.ImageBlocked;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Judges a scanned digest against the abuse blocklist and settles every submission of it (ADR 0042), the one place both
 * happen: for a new scan, for a cached one, when a newer list arrives, and when an admin blocks or unblocks. A digest the
 * list names is blocked for every renter who has it; one that was ready is announced ({@link ImageBlocked}), so sessions
 * of it that haven't started are cancelled and each owner is told. Nothing is ready until a list has judged it: with no
 * list cached yet, a checked image waits for one.
 */
@Component
class ImageJudge {

    private static final Logger log = LoggerFactory.getLogger(ImageJudge.class);
    static final String WAITING_FOR_BLOCKLIST = "blocklist";

    private final ScanStore scans;
    private final ImageStore images;
    private final Blocklists blocklists;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final ImagesMetrics metrics;

    ImageJudge(
            ScanStore scans,
            ImageStore images,
            Blocklists blocklists,
            JdbcClient jdbc,
            ApplicationEventPublisher events,
            ImagesMetrics metrics) {
        this.scans = scans;
        this.images = images;
        this.blocklists = blocklists;
        this.jdbc = jdbc;
        this.events = events;
        this.metrics = metrics;
    }

    /** A new scan, judged by the list cached now (if there is one). */
    @Transactional(propagation = Propagation.MANDATORY)
    void judgeNew(String digest, Instant now) {
        blocklists.programs().ifPresent(programs -> judge(digest, programs, now));
    }

    /** Judges {@code digest} by {@code programs}: blocked if the list names a file of it, else judged by that version. */
    @Transactional(propagation = Propagation.MANDATORY)
    void judge(String digest, Blocklists.Programs programs, Instant now) {
        var listed = scans.listed(digest, programs.sha256());
        scans.judged(digest, programs.version());
        if (listed.isPresent()) {
            var file = listed.get();
            log.info(
                    "Image {} holds a program on blocklist {} (layer {})",
                    digest,
                    programs.version(),
                    file.layer() + 1);
            block(
                    digest,
                    "blocklisted",
                    ImageVerdicts.BLOCKLISTED,
                    "/" + file.path() + " (layer " + (file.layer() + 1) + ")",
                    now);
        } else {
            settleWaiting(digest, now);
        }
    }

    /** One submission settled from its digest's scan: blocked, ready, or waiting for a blocklist to judge it. */
    @Transactional(propagation = Propagation.MANDATORY)
    void settle(ImageStore.Row row, ScanStore.Scan scan, Instant now) {
        if (scan.blocked()) {
            images.finish(
                    row.id(),
                    ImageStore.State.BLOCKED,
                    scan.digest(),
                    scan.blockedReason(),
                    words(scan.blockedReason()),
                    now);
            metrics.ended("blocked");
        } else if (scan.judged()) {
            images.finish(row.id(), ImageStore.State.READY, scan.digest(), null, null, now);
            metrics.ended("ready");
        } else {
            jdbc.sql("""
                            update images.images
                            set state = 'checking', phase = :phase, claimed_by = null, claim_expires_at = null,
                                done_bytes = 0, total_bytes = 0, updated_at = :now
                            where id = :id
                            """)
                    .param("phase", WAITING_FOR_BLOCKLIST)
                    .param("now", Timestamp.from(now))
                    .param("id", row.id())
                    .update();
        }
    }

    /** The submissions of {@code digest} that waited for a blocklist, settled now that one has judged it. */
    private void settleWaiting(String digest, Instant now) {
        var scan = scans.find(digest).orElseThrow();
        jdbc.sql("""
                        select * from images.images
                        where digest = :digest and state = 'checking' and phase = :phase and claimed_by is null
                        for update
                        """)
                .param("digest", digest)
                .param("phase", WAITING_FOR_BLOCKLIST)
                .query(ImageStore::row)
                .list()
                .forEach(row -> settle(row, scan, now));
    }

    static String words(@Nullable String reason) {
        return "admin".equals(reason) ? ImageVerdicts.ADMIN : ImageVerdicts.BLOCKLISTED;
    }

    /**
     * Blocks {@code digest} for everyone who has it: its scan, and every submission not blocked already. The ones that were
     * ready, or waiting, are announced.
     *
     * @param path the listed program's path, for admins only; null for an admin's block
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void block(String digest, String reason, String detail, @Nullable String path, Instant now) {
        scans.block(digest, reason, detail, path, now);
        var affected = images.withDigest(digest);
        for (var row : affected) {
            images.finish(row.id(), ImageStore.State.BLOCKED, digest, reason, detail, now);
        }
        var announced = affected.stream()
                .filter(row -> row.state() == ImageStore.State.READY)
                .map(row -> new ImageBlocked.Blocked(row.id(), row.userId(), row.reference()))
                .toList();
        metrics.newlyBlocked(reason, affected.size());
        if (!announced.isEmpty()) {
            events.publishEvent(new ImageBlocked(digest, announced, reason));
        }
    }

    /** An admin's block lifted: the digest's scan and its submissions blocked by it are ready again, if the list allows. */
    @Transactional(propagation = Propagation.MANDATORY)
    void unblock(String digest, Instant now) {
        scans.unblock(digest);
        var scan = scans.find(digest).orElseThrow();
        List<ImageStore.Row> blocked =
                jdbc.sql("""
                        select * from images.images where digest = :digest and state = 'blocked' and reason = 'admin'
                        for update
                        """).param("digest", digest).query(ImageStore::row).list();
        blocked.forEach(row -> settle(row, scan, now));
        blocklists.programs().ifPresent(programs -> judge(digest, programs, now));
    }
}
