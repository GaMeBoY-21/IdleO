package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AccountLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Payouts' signals for linked accounts (ADR 0052): the same PAN, and the same UPI ID to be paid at. */
@Component
class PayoutsAccountLinks implements AccountLinks {

    private final JdbcClient jdbc;

    PayoutsAccountLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AccountLink> linkedTo(UUID user) {
        var links = new ArrayList<AccountLink>();
        links.addAll(jdbc.sql("""
                        select other.user_id, other.pan_last4
                        from payouts.pans mine
                        join payouts.pans other on other.pan_lookup = mine.pan_lookup and other.user_id <> mine.user_id
                        where mine.user_id = :user
                        limit 50
                        """)
                .param("user", user)
                .query((rs, n) -> new AccountLink(
                        rs.getObject("user_id", UUID.class),
                        "same_pan",
                        Strength.STRONG,
                        "The same PAN, ending " + rs.getString("pan_last4"),
                        null))
                .list());
        links.addAll(jdbc.sql("""
                        select distinct other.user_id, other.vpa
                        from payouts.methods mine
                        join payouts.methods other on lower(other.vpa) = lower(mine.vpa) and other.user_id <> mine.user_id
                        where mine.user_id = :user
                        limit 50
                        """)
                .param("user", user)
                .query((rs, n) -> new AccountLink(
                        rs.getObject("user_id", UUID.class),
                        "same_upi",
                        Strength.STRONG,
                        "The same UPI ID, " + rs.getString("vpa"),
                        null))
                .list());
        return links;
    }
}
