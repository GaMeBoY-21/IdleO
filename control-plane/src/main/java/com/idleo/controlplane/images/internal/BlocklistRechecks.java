package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.hosts.BlocklistCached;
import com.idleo.controlplane.hosts.Blocklists;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every cached image judged again by the newest abuse blocklist (ADR 0042), from the hashes its check kept: when this
 * instance caches a newer list ({@link BlocklistCached}, published locally by the hosts module), and every minute besides,
 * so an instance that started after the list arrived, or a judgement that failed halfway, catches up. Idempotent: a scan is
 * judged once per version, in batches each instance locks for itself.
 */
@Component
class BlocklistRechecks {

    private static final Logger log = LoggerFactory.getLogger(BlocklistRechecks.class);
    private static final int BATCH = 50;

    private final Blocklists blocklists;
    private final ScanStore scans;
    private final ImageJudge judge;
    private final TransactionTemplate transactions;
    private final Clock clock;

    BlocklistRechecks(
            Blocklists blocklists,
            ScanStore scans,
            ImageJudge judge,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.blocklists = blocklists;
        this.scans = scans;
        this.judge = judge;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Async
    @EventListener
    void on(BlocklistCached cached) {
        recheck();
    }

    @Scheduled(fixedDelayString = "${images.recheck-every}")
    void scheduled() {
        recheck();
    }

    /** Judges every scan the newest list hasn't judged yet; returns how many. */
    int recheck() {
        var programs = blocklists.programs();
        if (programs.isEmpty()) {
            return 0;
        }
        int judged = 0;
        try {
            while (true) {
                int batch = Objects.requireNonNull(transactions.execute(status -> {
                    var now = Instant.now(clock);
                    var digests = scans.toJudge(programs.get().version(), BATCH);
                    digests.forEach(digest -> judge.judge(digest, programs.get(), now));
                    return digests.size();
                }));
                judged += batch;
                if (batch < BATCH) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Judging images again by blocklist {} stopped; it carries on shortly",
                    programs.get().version(),
                    e);
        }
        if (judged > 0) {
            log.info(
                    "Judged {} cached images by blocklist {}",
                    judged,
                    programs.get().version());
        }
        return judged;
    }
}
