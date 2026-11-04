package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What another admin approves in payouts (ADR 0051): sending a held or failed payout, and an export with every PAN. */
@Configuration(proxyBeanMethods = false)
class PayoutsGatedActions {

    @Bean
    Approvals.GatedAction releasePayout(PayoutRuns runs) {
        return Approvals.action(
                AdminPayoutsController.RELEASE, AdminPermission.PAYOUTS_RELEASE, Approvals.Cooling.MONEY, run -> {
                    var outcome = runs.payNow(
                            UUID.fromString(run.params().get("userId").asString()), "retry", run.reason(), run.actor());
                    if (outcome.notPaid() != null) {
                        throw ApiProblem.of(Problem.ACTION_REFUSED, "It wasn't paid: " + outcome.notPaid().words + ".");
                    }
                });
    }

    @Bean
    @ConditionalOnWebApplication
    Approvals.GatedAction exportTds(AdminPayoutsController payouts) {
        return Approvals.fileAction(
                AdminPayoutsController.TDS_EXPORT,
                AdminPermission.EXPORT_CREATE,
                Approvals.Cooling.MONEY,
                run -> payouts.tdsFile(run.params().get("quarter").asString(), run.reason(), run.actor()));
    }
}
