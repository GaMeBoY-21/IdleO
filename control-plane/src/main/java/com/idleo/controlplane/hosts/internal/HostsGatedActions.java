package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What another admin approves in hosts (ADR 0051). Suspending is never gated; lifting a suspension is. Pausing a rollout
 * is never gated; widening one is (ADR 0056).
 */
@Configuration(proxyBeanMethods = false)
class HostsGatedActions {

    @Bean
    Approvals.GatedAction reactivateHost(HostActivation activation) {
        return Approvals.action(
                AdminHostsController.REACTIVATE,
                AdminPermission.HOSTS_ACTIVATE,
                Approvals.Cooling.ACCESS,
                run -> activation.activate(
                        UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }

    /** Widening a rollout (ADR 0056): like running a kill switch again, it never waits out a cooling-off. */
    @Bean
    Approvals.GatedAction widenRollout(AgentRollouts rollouts) {
        return Approvals.action(
                AdminRolloutsController.WIDEN,
                AdminPermission.ROLLOUTS_WIDEN,
                Approvals.Cooling.NONE,
                run -> rollouts.widen(
                        run.params().get("id").asString(),
                        run.params().get("percent").asInt(),
                        run.reason(),
                        run.actor()));
    }
}
