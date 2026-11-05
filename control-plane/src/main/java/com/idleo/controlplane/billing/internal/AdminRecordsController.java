package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.PersonalDataAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the console's pages read of a person's money and of one payment (ADR 0052). */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnWebApplication
class AdminRecordsController {

    private final JdbcClient jdbc;
    private final PersonalDataAccess accessLog;

    AdminRecordsController(JdbcClient jdbc, PersonalDataAccess accessLog) {
        this.jdbc = jdbc;
        this.accessLog = accessLog;
    }

    /** One lot of credit: free or bought, what it was, and what's left. */
    record AdminCreditLot(
            UUID id,
            String kind,
            String sourceType,
            long amountPaise,
            long remainingPaise,
            @Nullable Instant expiresAt,
            Instant createdAt) {}

    /** A person's wallet: what they hold, what's reserved for running sessions, and the lots it's made of, newest first. */
    record AdminCredit(long balancePaise, long reservedPaise, List<AdminCreditLot> lots) {}

    @RequiresPermission(AdminPermission.MONEY_READ)
    @GetMapping("/users/{id}/credit")
    AdminCredit credit(@PathVariable UUID id) {
        record Wallet(long balance, long reserved) {}
        var wallet = jdbc.sql("select balance_paise, reserved_paise from billing.wallets where user_id = :id")
                .param("id", id)
                .query((rs, n) -> new Wallet(rs.getLong("balance_paise"), rs.getLong("reserved_paise")))
                .optional()
                .orElse(new Wallet(0, 0));
        return new AdminCredit(wallet.balance(), wallet.reserved(), lots("user_id = :id", id));
    }

    private List<AdminCreditLot> lots(String where, UUID id) {
        return jdbc.sql("select * from billing.credit_lots where " + where + " order by created_at desc limit 200")
                .param("id", id)
                .query((rs, n) -> new AdminCreditLot(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("source_type"),
                        rs.getLong("amount_paise"),
                        rs.getLong("remaining_paise"),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** One payment's part of a refund. */
    record AdminRefundPart(
            UUID refundId, long amountPaise, @Nullable String razorpayRefundId, String status) {}

    /** A document made from a payment. */
    record AdminPaymentDocument(UUID id, String number, String kind) {}

    /** A chargeback against a payment. */
    record AdminPaymentChargeback(UUID id, String disputeId, long amountPaise, String status) {}

    /** What the payment reconciliation found about a payment. */
    record AdminPaymentFinding(UUID id, String kind, String state) {}

    /**
     * One payment, everything about it apart from its timeline: the top-up, the credit it became, its refunds, documents,
     * chargebacks, and what the reconciliation found.
     */
    record AdminPaymentRecord(
            UUID id,
            UUID userId,
            long amountPaise,
            String status,
            String orderId,
            @Nullable String paymentId,
            @Nullable String method,
            @Nullable String cardNetwork,
            @Nullable String cardLast4,
            @Nullable String failureReason,
            @Nullable String creditedBy,
            Instant createdAt,
            @Nullable Instant paidAt,
            List<AdminCreditLot> lots,
            List<AdminRefundPart> refunds,
            List<AdminPaymentDocument> documents,
            List<AdminPaymentChargeback> chargebacks,
            List<AdminPaymentFinding> findings) {}

    @RequiresPermission(AdminPermission.PAYMENTS_READ)
    @GetMapping("/payments/{id}")
    AdminPaymentRecord payment(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        var record = jdbc.sql("select * from billing.topups where id = :id")
                .param("id", id)
                .query((rs, n) -> new AdminPaymentRecord(
                        id,
                        rs.getObject("user_id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("status"),
                        rs.getString("order_id"),
                        rs.getString("payment_id"),
                        rs.getString("method"),
                        rs.getString("card_network"),
                        rs.getString("card_last4"),
                        rs.getString("failure_reason"),
                        rs.getString("credited_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("paid_at") == null
                                ? null
                                : rs.getTimestamp("paid_at").toInstant(),
                        lots("source_id = :id", id),
                        jdbc.sql("""
                                        select refund_id, amount_paise, razorpay_refund_id, status
                                        from billing.refund_parts where topup_id = :id order by created_at
                                        """)
                                .param("id", id)
                                .query((part, m) -> new AdminRefundPart(
                                        part.getObject("refund_id", UUID.class),
                                        part.getLong("amount_paise"),
                                        part.getString("razorpay_refund_id"),
                                        part.getString("status")))
                                .list(),
                        jdbc.sql(
                                        "select id, number, kind from billing.invoices where topup_id = :id order by issued_at")
                                .param("id", id)
                                .query((doc, m) -> new AdminPaymentDocument(
                                        doc.getObject("id", UUID.class),
                                        doc.getString("number"),
                                        doc.getString("kind")))
                                .list(),
                        jdbc.sql("""
                                        select id, dispute_id, amount_paise, status from billing.chargebacks
                                        where topup_id = :id order by opened_at
                                        """)
                                .param("id", id)
                                .query((back, m) -> new AdminPaymentChargeback(
                                        back.getObject("id", UUID.class),
                                        back.getString("dispute_id"),
                                        back.getLong("amount_paise"),
                                        back.getString("status")))
                                .list(),
                        jdbc.sql("""
                                        select id, kind, state from billing.payment_mismatches
                                        where subject = :order or subject = :payment order by id desc limit 20
                                        """)
                                .param("order", rs.getString("order_id"))
                                .param("payment", rs.getString("payment_id"))
                                .query((found, m) -> new AdminPaymentFinding(
                                        found.getObject("id", UUID.class),
                                        found.getString("kind"),
                                        found.getString("state")))
                                .list()))
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such payment."));
        accessLog.viewed(admin, record.userId(), id.toString());
        return record;
    }
}
