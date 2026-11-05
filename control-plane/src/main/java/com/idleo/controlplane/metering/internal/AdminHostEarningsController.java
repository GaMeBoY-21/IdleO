package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a host's page reads of what it earned (ADR 0052): what it was settled, whoever it was paid to. */
@RestController
@ConditionalOnWebApplication
class AdminHostEarningsController {

    private final JdbcClient jdbc;

    AdminHostEarningsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record AdminHostEarnings(long chargedPaise, long hostSharePaise, long minutes) {}

    @RequiresPermission(AdminPermission.MONEY_READ)
    @GetMapping("/admin/v1/hosts/{id}/earnings")
    AdminHostEarnings earnings(@PathVariable UUID id) {
        return jdbc.sql("""
                        select coalesce(sum(amount_paise), 0) charged, coalesce(sum(host_share_paise), 0) share,
                               coalesce(sum(minutes), 0) minutes
                        from metering.settlement_parts where host_id = :id
                        """)
                .param("id", id)
                .query((rs, n) ->
                        new AdminHostEarnings(rs.getLong("charged"), rs.getLong("share"), rs.getLong("minutes")))
                .single();
    }
}
