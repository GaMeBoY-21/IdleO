package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The console's search over money (ADR 0052): Razorpay's payment, order and refund ids, and document numbers. */
@Configuration(proxyBeanMethods = false)
class BillingSearch {

    @Bean
    AdminSearch paymentsAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("payment", AdminPermission.PAYMENTS_READ, (query, limit) -> {
            var id = query.id();
            var statement = id.isPresent()
                    ? jdbc.sql("select id, order_id, payment_id, status from billing.topups where id = :id")
                            .param("id", id.get())
                    : jdbc.sql("""
                                    select id, order_id, payment_id, status from billing.topups
                                    where payment_id = :text or order_id = :text
                                    """).param("text", query.text());
            return statement
                    .query((rs, n) -> {
                        var payment = rs.getString("payment_id");
                        return new AdminSearch.Hit(
                                "payment",
                                rs.getObject("id").toString(),
                                payment != null ? payment : rs.getString("order_id"),
                                rs.getString("status"));
                    })
                    .list();
        });
    }

    @Bean
    AdminSearch refundsAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("refund", AdminPermission.REFUNDS_READ, (query, limit) -> {
            var id = query.id();
            if (id.isPresent()) {
                return jdbc.sql("select id, status from billing.credit_refunds where id = :id")
                        .param("id", id.get())
                        .query((rs, n) -> new AdminSearch.Hit(
                                "refund", rs.getObject("id").toString(), "Refund", rs.getString("status")))
                        .list();
            }
            return jdbc.sql("""
                            select refund_id, razorpay_refund_id, status from billing.refund_parts
                            where razorpay_refund_id = :text
                            """)
                    .param("text", query.text())
                    .query((rs, n) -> new AdminSearch.Hit(
                            "refund",
                            rs.getObject("refund_id").toString(),
                            rs.getString("razorpay_refund_id"),
                            rs.getString("status")))
                    .list();
        });
    }

    @Bean
    AdminSearch invoicesAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of(
                "invoice",
                AdminPermission.INVOICES_READ,
                (query, limit) -> jdbc.sql("select id, number, kind from billing.invoices where number = :number")
                        .param("number", query.upper())
                        .query((rs, n) -> new AdminSearch.Hit(
                                "invoice", rs.getObject("id").toString(), rs.getString("number"), rs.getString("kind")))
                        .list());
    }
}
