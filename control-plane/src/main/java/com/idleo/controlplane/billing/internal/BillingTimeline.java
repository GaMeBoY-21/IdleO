package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import com.idleo.controlplane.Rupees;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Billing's part of the console's timeline (ADR 0052): top-ups, refunds, documents, and a payment's own events. */
@Configuration(proxyBeanMethods = false)
class BillingTimeline {

    @Bean
    AdminTimeline topUpsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("top_up", AdminPermission.PAYMENTS_READ, (subject, window) -> {
            if (!subject.is("user")) {
                return List.of();
            }
            return jdbc.sql("""
                            select id, amount_paise, status, created_at from billing.topups
                            where user_id = :user and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("user", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "top_up:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "top_up",
                            "Top-up of " + Rupees.format(rs.getLong("amount_paise")),
                            rs.getString("status"),
                            "payment",
                            rs.getString("id")))
                    .list();
        });
    }

    /** Razorpay's webhooks about one payment, by the ids alone that are kept of them. */
    @Bean
    AdminTimeline paymentEventsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("razorpay", AdminPermission.PAYMENTS_READ, (subject, window) -> {
            if (!subject.is("payment")) {
                return List.of();
            }
            return jdbc.sql("""
                            select e.event_id, e.event, e.received_at
                            from billing.payment_events e
                            join billing.topups t on t.payment_id = e.payment_id or t.order_id = e.order_id
                            where t.id = :id and e.received_at <= :until
                            order by e.received_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "razorpay:" + rs.getString("event_id"),
                            rs.getTimestamp("received_at").toInstant(),
                            "razorpay",
                            rs.getString("event"),
                            null,
                            null,
                            null))
                    .list();
        });
    }

    @Bean
    AdminTimeline refundsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("refund", AdminPermission.REFUNDS_READ, (subject, window) -> {
            var statement = switch (subject.kind()) {
                case "user" -> jdbc.sql("""
                                        select id, approved_paise, requested_paise, status, requested_at
                                        from billing.credit_refunds
                                        where user_id = :id and requested_at <= :until
                                        order by requested_at desc
                                        limit :limit
                                        """);
                case "payment" -> jdbc.sql("""
                                        select r.id, r.approved_paise, r.requested_paise, r.status, r.requested_at
                                        from billing.credit_refunds r
                                        where r.id in (select refund_id from billing.refund_parts where topup_id = :id)
                                          and r.requested_at <= :until
                                        order by r.requested_at desc
                                        limit :limit
                                        """);
                default -> null;
            };
            if (statement == null) {
                return List.of();
            }
            return statement
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> {
                        var approved = rs.getObject("approved_paise", Long.class);
                        var requested = rs.getObject("requested_paise", Long.class);
                        var amount = approved != null ? approved : requested;
                        return new AdminTimeline.TimelineEvent(
                                "refund:" + rs.getString("id"),
                                rs.getTimestamp("requested_at").toInstant(),
                                "refund",
                                amount == null ? "Refund of all they can" : "Refund of " + Rupees.format(amount),
                                rs.getString("status"),
                                "refund",
                                rs.getString("id"));
                    })
                    .list();
        });
    }

    @Bean
    AdminTimeline invoicesTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("invoice", AdminPermission.INVOICES_READ, (subject, window) -> {
            var statement = switch (subject.kind()) {
                case "user" -> jdbc.sql("""
                                        select id, number, kind, issued_at from billing.invoices
                                        where user_id = :id and issued_at <= :until
                                        order by issued_at desc
                                        limit :limit
                                        """);
                case "payment" -> jdbc.sql("""
                                        select id, number, kind, issued_at from billing.invoices
                                        where topup_id = :id and issued_at <= :until
                                        order by issued_at desc
                                        limit :limit
                                        """);
                default -> null;
            };
            if (statement == null) {
                return List.of();
            }
            return statement
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "invoice:" + rs.getString("id"),
                            rs.getTimestamp("issued_at").toInstant(),
                            "invoice",
                            rs.getString("number"),
                            rs.getString("kind").replace('_', ' '),
                            "invoice",
                            rs.getString("id")))
                    .list();
        });
    }
}
