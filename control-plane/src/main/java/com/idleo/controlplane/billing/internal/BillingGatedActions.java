package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What another admin approves in billing (ADR 0051): refunds above the limit and every manual money adjustment. */
@Configuration(proxyBeanMethods = false)
class BillingGatedActions {

    static final String CREDIT_GRANT = "credit.grant";
    static final String CREDIT_REVERSE = "credit.reverse";
    static final String REFUND_APPROVE = "refund.approve";
    static final String CLAWBACK_WAIVE = "clawback.waive";
    static final String CREDIT_NOTE = "invoice.credit_note";

    @Bean
    Approvals.GatedAction grantCredit(CreditGrants grants) {
        return Approvals.action(
                CREDIT_GRANT,
                AdminPermission.MONEY_ADJUST,
                Approvals.Cooling.MONEY,
                run -> grants.grant(
                        UUID.fromString(run.params().get("userId").asString()),
                        Paise.of(run.params().get("paise").asLong()),
                        run.reason(),
                        run.approvalId().toString(),
                        run.actor()));
    }

    @Bean
    Approvals.GatedAction reverseCredit(CreditGrants grants) {
        return Approvals.action(
                CREDIT_REVERSE,
                AdminPermission.MONEY_ADJUST,
                Approvals.Cooling.MONEY,
                run -> grants.reverse(UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }

    @Bean
    Approvals.GatedAction approveRefund(CreditRefunds refunds) {
        return Approvals.action(
                REFUND_APPROVE,
                AdminPermission.REFUNDS_APPROVE,
                Approvals.Cooling.MONEY,
                run -> refunds.approve(
                        UUID.fromString(run.params().get("id").asString()),
                        Paise.of(run.params().get("amountPaise").asLong()),
                        run.reason(),
                        run.actor()));
    }

    @Bean
    Approvals.GatedAction waiveClawback(Clawbacks clawbacks) {
        return Approvals.action(
                CLAWBACK_WAIVE,
                AdminPermission.CLAWBACKS_WAIVE,
                Approvals.Cooling.MONEY,
                run -> clawbacks.waive(UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }

    @Bean
    Approvals.GatedAction issueCreditNote(Invoices invoices) {
        return Approvals.action(
                CREDIT_NOTE,
                AdminPermission.INVOICES_CREDIT_NOTE,
                Approvals.Cooling.MONEY,
                run -> invoices.creditNoteByAdmin(
                        UUID.fromString(run.params().get("id").asString()),
                        Paise.of(run.params().get("amountPaise").asLong()),
                        run.reason(),
                        run.actor()));
    }
}
