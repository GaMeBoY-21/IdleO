package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A session's minutes as both witnesses saw them, and how each was charged, for the admin console (ADR 0028): what an
 * admin reads beside a renter's report. Read-only.
 */
@RestController
@ConditionalOnWebApplication
class AdminUsageController {

    private final JdbcClient jdbc;

    AdminUsageController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One minute: what the agent reported (its attempt, GPU seconds and utilisation) and whether the control plane's
     * lease covered it (and for which attempt), then whether it was charged, in which settlement, or waived and
     * refunded.
     */
    record AdminUsageMinute(
            int minuteIndex,
            Instant minuteAt,
            boolean agentReported,
            @Nullable Integer agentAttempt,
            @Nullable Integer gpuSeconds,
            @Nullable Integer avgUtilPct,
            boolean leaseChecked,
            boolean leaseAlive,
            @Nullable Integer leaseAttempt,
            boolean billed,
            @Nullable UUID settlementTxn,
            boolean waived,
            @Nullable UUID refundTxn) {}

    /** One settlement: its window, the minutes it charged, and how the amount was split. */
    record AdminSettlement(
            UUID txnId,
            int windowNumber,
            @Nullable Instant windowEnd,
            int minutes,
            long amountPaise,
            long hostSharePaise,
            long commissionPaise,
            Instant settledAt) {}

    /** A lost attempt's waiver: from when its minutes were given back, and what was refunded of them. */
    record AdminWaiver(
            int attempt,
            UUID hostId,
            @Nullable Instant checkpointAt,
            Instant waivedFrom,
            int refundedMinutes,
            long refundedPaise) {}

    record AdminUsage(
            UUID sessionId,
            List<AdminUsageMinute> minutes,
            List<AdminSettlement> settlements,
            List<AdminWaiver> waivers) {}

    @GetMapping("/admin/v1/sessions/{id}/usage")
    AdminUsage get(@PathVariable UUID id) {
        boolean metered = jdbc.sql("select exists (select 1 from metering.metered_sessions where session_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
        if (!metered) {
            throw ApiProblem.of(Problem.NOT_FOUND, "No session with that id ran.");
        }
        var minutes = jdbc.sql("""
                        select * from metering.usage_minutes where session_id = :id order by minute_index, minute_at
                        """)
                .param("id", id)
                .query((rs, n) -> new AdminUsageMinute(
                        rs.getInt("minute_index"),
                        rs.getTimestamp("minute_at").toInstant(),
                        rs.getBoolean("agent_reported"),
                        rs.getObject("agent_attempt", Integer.class),
                        rs.getObject("gpu_seconds", Integer.class),
                        rs.getObject("avg_util_pct", Integer.class),
                        rs.getBoolean("lease_checked"),
                        rs.getBoolean("lease_alive"),
                        rs.getObject("lease_attempt", Integer.class),
                        rs.getBoolean("billed"),
                        rs.getObject("settlement_txn", UUID.class),
                        rs.getBoolean("waived"),
                        rs.getObject("refund_txn", UUID.class)))
                .list();
        var settlements = jdbc.sql("select * from metering.settlements where session_id = :id order by window_number")
                .param("id", id)
                .query((rs, n) -> new AdminSettlement(
                        rs.getObject("txn_id", UUID.class),
                        rs.getInt("window_number"),
                        rs.getTimestamp("window_end") == null
                                ? null
                                : rs.getTimestamp("window_end").toInstant(),
                        rs.getInt("minutes"),
                        rs.getLong("amount_paise"),
                        rs.getLong("host_share_paise"),
                        rs.getLong("commission_paise"),
                        rs.getTimestamp("settled_at").toInstant()))
                .list();
        var waivers = jdbc.sql("select * from metering.waivers where session_id = :id order by attempt")
                .param("id", id)
                .query((rs, n) -> new AdminWaiver(
                        rs.getInt("attempt"),
                        rs.getObject("host_id", UUID.class),
                        rs.getTimestamp("checkpoint_at") == null
                                ? null
                                : rs.getTimestamp("checkpoint_at").toInstant(),
                        rs.getTimestamp("waived_from").toInstant(),
                        rs.getInt("refunded_minutes"),
                        rs.getLong("refunded_paise")))
                .list();
        return new AdminUsage(id, minutes, settlements, waivers);
    }
}
