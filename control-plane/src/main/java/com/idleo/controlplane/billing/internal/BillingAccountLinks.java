package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AccountLinks;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Billing's signal for linked accounts (ADR 0052): payments from the same UPI ID, told by their keyed hashes. */
@Component
class BillingAccountLinks implements AccountLinks {

    private static final int MOST = 50;

    private final JdbcClient jdbc;

    BillingAccountLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A family can share a UPI ID, so it says something, but not by itself that they're one person. */
    @Override
    public List<AccountLink> linkedTo(UUID user) {
        return jdbc.sql("""
                        select other.user_id, max(other.created_at) as at
                        from billing.payer_hashes mine
                        join billing.payer_hashes other
                          on other.key_version = mine.key_version and other.hash = mine.hash
                             and other.user_id <> mine.user_id
                        where mine.user_id = :user
                        group by other.user_id
                        order by at desc
                        limit :most
                        """)
                .param("user", user)
                .param("most", MOST)
                .query((rs, n) -> new AccountLink(
                        rs.getObject("user_id", UUID.class),
                        "same_payer",
                        Strength.MEDIUM,
                        "Paid from the same UPI ID",
                        rs.getTimestamp("at").toInstant()))
                .list();
    }
}
