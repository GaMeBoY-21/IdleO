package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.Approvals;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * What another admin approves in billing (ADR 0051): refunds above the limit, every manual money adjustment and its
 * reversal (ADR 0055), and closing or reopening a month.
 */
@Configuration(proxyBeanMethods = false)
class BillingGatedActions {

    static final String CREDIT_GRANT = "credit.grant";
    static final String CREDIT_REVERSE = "credit.reverse";
    static final String REFUND_APPROVE = "refund.approve";
    static final String CLAWBACK_WAIVE = "clawback.waive";
    static final String CREDIT_NOTE = "invoice.credit_note";
    static final String ADJUSTMENT = "money.adjustment";
    static final String ADJUSTMENT_REVERSE = "money.adjustment.reverse";
    static final String PERIOD_CLOSE = "period.close";
    static final String PERIOD_REOPEN = "period.reopen";

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

    @Bean
    Approvals.GatedAction postAdjustment(Adjustments adjustments) {
        return Approvals.action(
                ADJUSTMENT,
                AdminPermission.MONEY_ADJUST,
                Approvals.Cooling.MONEY,
                run -> adjustments.post(run.approvalId(), request(run.params(), run.reason()), run.actor()));
    }

    @Bean
    Approvals.GatedAction reverseAdjustment(Adjustments adjustments) {
        return Approvals.action(
                ADJUSTMENT_REVERSE,
                AdminPermission.MONEY_ADJUST,
                Approvals.Cooling.MONEY,
                run -> adjustments.reverse(
                        run.approvalId(),
                        UUID.fromString(run.params().get("id").asString()),
                        run.reason(),
                        run.params().get("reference").asString(),
                        run.actor()));
    }

    @Bean
    Approvals.GatedAction closePeriod(Periods periods) {
        return Approvals.action(
                PERIOD_CLOSE,
                AdminPermission.PERIODS_CLOSE,
                Approvals.Cooling.MONEY,
                run -> periods.close(
                        run.approvalId(),
                        YearMonth.parse(run.params().get("month").asString()),
                        run.reason(),
                        run.actor()));
    }

    @Bean
    Approvals.GatedAction reopenPeriod(Periods periods) {
        return Approvals.action(
                PERIOD_REOPEN,
                AdminPermission.PERIODS_CLOSE,
                Approvals.Cooling.MONEY,
                run -> periods.reopen(
                        run.approvalId(),
                        YearMonth.parse(run.params().get("month").asString()),
                        run.reason(),
                        run.actor()));
    }

    /** An adjustment's request from its approval's parameters, as {@link AdminAdjustmentsController} wrote them. */
    static Adjustments.Request request(JsonNode params, String reason) {
        var legs = new ArrayList<Adjustments.Leg>();
        for (JsonNode leg : params.get("legs")) {
            var owner = leg.get("ownerId");
            legs.add(new Adjustments.Leg(
                    Account.fromDb(leg.get("account").asString()),
                    owner == null || owner.isNull() ? null : UUID.fromString(owner.asString()),
                    Paise.of(leg.get("amountPaise").asLong()),
                    Direction.fromDb(leg.get("direction").asString())));
        }
        var month = params.get("correctsMonth");
        return new Adjustments.Request(
                Adjustments.Template.fromDb(params.get("template").asString()),
                legs,
                reason,
                params.get("reference").asString(),
                month == null || month.isNull() ? null : YearMonth.parse(month.asString()));
    }
}
