package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a person's page reads of how they're paid (ADR 0052): their PAN's standing and every UPI ID they've given. */
@RestController
@ConditionalOnWebApplication
class AdminPayoutDetailsController {

    private final JdbcClient jdbc;

    AdminPayoutDetailsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record AdminPanStanding(String last4, String state, Instant submittedAt) {}

    record AdminPayoutMethod(
            UUID id,
            String vpa,
            String state,
            Instant checkedAt,
            @Nullable Instant endedAt) {}

    record AdminPayoutDetails(@Nullable AdminPanStanding pan, List<AdminPayoutMethod> methods) {}

    @RequiresPermission(AdminPermission.VERIFICATIONS_READ)
    @GetMapping("/admin/v1/users/{id}/payout-details")
    AdminPayoutDetails details(@PathVariable UUID id) {
        var pan = jdbc.sql("select pan_last4, state, submitted_at from payouts.pans where user_id = :id")
                .param("id", id)
                .query((rs, n) -> new AdminPanStanding(
                        rs.getString("pan_last4"),
                        rs.getString("state"),
                        rs.getTimestamp("submitted_at").toInstant()))
                .optional()
                .orElse(null);
        var methods = jdbc.sql("""
                        select id, vpa, state, checked_at, ended_at from payouts.methods
                        where user_id = :id order by id desc limit 50
                        """)
                .param("id", id)
                .query((rs, n) -> new AdminPayoutMethod(
                        rs.getObject("id", UUID.class),
                        rs.getString("vpa"),
                        rs.getString("state"),
                        rs.getTimestamp("checked_at").toInstant(),
                        rs.getTimestamp("ended_at") == null
                                ? null
                                : rs.getTimestamp("ended_at").toInstant()))
                .list();
        return new AdminPayoutDetails(pan, methods);
    }
}
