package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The console's search over payouts (ADR 0052): by ours or RazorpayX's id, and who a UPI ID or a PAN belongs to, by
 * exact match only. A PAN is sealed, so it's found by its keyed lookup value, never by opening every row.
 */
@Configuration(proxyBeanMethods = false)
class PayoutsSearch {

    private static final String PAN_SHAPE = "[A-Z]{5}[0-9]{4}[A-Z]";

    @Bean
    AdminSearch payoutsAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("payout", AdminPermission.PAYOUTS_READ, (query, limit) -> {
            var id = query.id();
            var statement = id.isPresent()
                    ? jdbc.sql("select id, razorpayx_id, status from payouts.payouts where id = :id")
                            .param("id", id.get())
                    : jdbc.sql("select id, razorpayx_id, status from payouts.payouts where razorpayx_id = :text")
                            .param("text", query.text());
            return statement
                    .query((rs, n) -> {
                        var theirs = rs.getString("razorpayx_id");
                        return new AdminSearch.SearchHit(
                                "payout",
                                rs.getObject("id").toString(),
                                theirs != null ? theirs : "Payout",
                                rs.getString("status"));
                    })
                    .list();
        });
    }

    @Bean
    AdminSearch upiAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("upi", AdminPermission.VERIFICATIONS_READ, (query, limit) -> {
            if (!query.text().contains("@")) {
                return List.of();
            }
            return jdbc.sql("""
                            select user_id, vpa, state from payouts.methods
                            where lower(vpa) = :vpa
                            order by id desc
                            limit :limit
                            """)
                    .param("vpa", query.lower())
                    .param("limit", limit)
                    .query((rs, n) -> new AdminSearch.SearchHit(
                            "user", rs.getObject("user_id").toString(), rs.getString("vpa"), rs.getString("state")))
                    .list();
        });
    }

    @Bean
    AdminSearch panAdminSearch(JdbcClient jdbc, PanSeal seal) {
        return AdminSearch.of("pan", AdminPermission.VERIFICATIONS_READ, (query, limit) -> {
            if (!query.upper().matches(PAN_SHAPE)) {
                return List.of();
            }
            return jdbc.sql("select user_id, pan_last4, state from payouts.pans where pan_lookup = :lookup")
                    .param("lookup", seal.lookup(query.upper()))
                    .query((rs, n) -> new AdminSearch.SearchHit(
                            "user",
                            rs.getObject("user_id").toString(),
                            "PAN ending " + rs.getString("pan_last4"),
                            rs.getString("state")))
                    .list();
        });
    }
}
