package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Activating and suspending hosts by operator command (ADR 0015), until onboarding's benchmark and
 * canary job exist (technical design line 644). Every change is an append-only row in {@code
 * hosts.status_changes}; asking for the status a host already has changes nothing.
 */
@Service
class HostActivation {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;

    HostActivation(JdbcClient jdbc, PlatformTransactionManager transactionManager, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
    }

    /** The outcome: {@code changed} is false when the host already had that status. */
    record Change(UUID hostId, String from, String to, boolean changed) {}

    /** Refused; nothing changed. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * Activates a host. Refused unless it has a valid, unrevoked certificate, a listing, and at least one
     * reported GPU that isn't gone.
     */
    Change activate(UUID hostId, String reason, String operator) {
        return change(hostId, HostStatus.ACTIVE, reason, operator);
    }

    /** Suspends a host: its agent is refused at its next connection (ADR 0011). */
    Change suspend(UUID hostId, String reason, String operator) {
        return change(hostId, HostStatus.SUSPENDED, reason, operator);
    }

    private Change change(UUID hostId, HostStatus to, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw new Refused("a reason is required");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var from = jdbc.sql("select status from hosts.hosts where id = :hostId for update")
                    .param("hostId", hostId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new Refused("there is no host " + hostId));
            if (from.equals(to.dbValue())) {
                return new Change(hostId, from, from, false);
            }
            var now = Instant.now(clock);
            if (to == HostStatus.ACTIVE) {
                requireReadyToActivate(hostId, now);
            }
            jdbc.sql("update hosts.hosts set status = :to, updated_at = :now, version = version + 1 where id = :hostId")
                    .param("to", to.dbValue())
                    .param("now", Timestamp.from(now))
                    .param("hostId", hostId)
                    .update();
            jdbc.sql("""
                            insert into hosts.status_changes (id, host_id, from_status, to_status, reason, changed_by, created_at)
                            values (:id, :hostId, :from, :to, :reason, :by, :now)
                            """)
                    .param("id", ids.newId())
                    .param("hostId", hostId)
                    .param("from", from)
                    .param("to", to.dbValue())
                    .param("reason", reason.strip())
                    .param("by", operator)
                    .param("now", Timestamp.from(now))
                    .update();
            return new Change(hostId, from, to.dbValue(), true);
        }));
    }

    private void requireReadyToActivate(UUID hostId, Instant now) {
        boolean certificate = jdbc.sql("""
                        select exists (select 1 from hosts.host_certificates
                                       where host_id = :hostId and revoked_at is null and not_after > :now)
                        """)
                .param("hostId", hostId)
                .param("now", Timestamp.from(now))
                .query(Boolean.class)
                .single();
        if (!certificate) {
            throw new Refused("host " + hostId + " has no valid certificate: enrol its agent first");
        }
        boolean listing = jdbc.sql("select exists (select 1 from hosts.price_listings where host_id = :hostId)")
                .param("hostId", hostId)
                .query(Boolean.class)
                .single();
        if (!listing) {
            throw new Refused("host " + hostId + " has no listing: its owner sets one with PUT /v1/hosts/{id}/listing");
        }
        boolean gpu = jdbc.sql(
                        "select exists (select 1 from hosts.host_gpus where host_id = :hostId and gone_at is null)")
                .param("hostId", hostId)
                .query(Boolean.class)
                .single();
        if (!gpu) {
            throw new Refused("host " + hostId + " has no reported GPU: its agent must connect first");
        }
    }
}
