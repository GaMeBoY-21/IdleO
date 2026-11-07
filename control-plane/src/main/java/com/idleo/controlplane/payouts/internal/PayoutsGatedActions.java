package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What another admin approves in payouts (ADR 0051): sending a held or failed payout, and releasing a batch (ADR 0055).
 * The TDS files, with every PAN, are billing's tax exports.
 */
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

    /** A batch, released as previewed, once (ADR 0055). */
    @Bean
    Approvals.GatedAction releaseBatch(PayoutRuns runs) {
        return Approvals.action(
                AdminPayoutBatchesController.RELEASE_BATCH,
                AdminPermission.PAYOUTS_RELEASE,
                Approvals.Cooling.MONEY,
                run -> {
                    try {
                        runs.release(UUID.fromString(run.params().get("id").asString()), run.approvalId(), run.actor());
                    } catch (PayoutRuns.Refused e) {
                        throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
                    }
                });
    }
}
