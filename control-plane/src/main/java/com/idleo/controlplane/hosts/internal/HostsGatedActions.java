package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What another admin approves in hosts (ADR 0051). Suspending is never gated; lifting a suspension is. */
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
}
