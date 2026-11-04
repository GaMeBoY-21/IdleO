package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What another admin approves in trust (ADR 0051): a refund above the limit, and clearing a review, which lifts a ban. */
@Configuration(proxyBeanMethods = false)
class TrustGatedActions {

    static final String DISPUTE_REFUND = "dispute.refund";
    static final String ABUSE_CLEAR = "abuse.clear";

    @Bean
    Approvals.GatedAction refundReport(Disputes disputes) {
        return Approvals.action(DISPUTE_REFUND, AdminPermission.DISPUTES_REFUND, Approvals.Cooling.MONEY, run -> {
            var outcome = disputes.refund(
                    UUID.fromString(run.params().get("id").asString()),
                    Paise.of(run.params().get("amountPaise").asLong()),
                    run.reason(),
                    run.params().get("flagHost").asBoolean(),
                    run.actor());
            if (outcome instanceof Disputes.Resolved.Refused refused) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, refused.why());
            }
        });
    }

    @Bean
    @ConditionalOnWebApplication
    Approvals.GatedAction clearAbuse(AdminAbuseController abuse) {
        return Approvals.action(
                ABUSE_CLEAR,
                AdminPermission.ABUSE_CLEAR,
                Approvals.Cooling.ACCESS,
                run -> abuse.clearNow(UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }
}
