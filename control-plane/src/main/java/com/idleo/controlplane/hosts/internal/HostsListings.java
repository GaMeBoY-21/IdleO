package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Hosts's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class HostsListings {

    @Bean
    AdminListing hostsListing() {
        return AdminHostsController.LIST;
    }

    @Bean
    AdminListing fleetListing() {
        return AdminFleetController.LIST;
    }

    @Bean
    AdminListing installsListing() {
        return HostInstalls.LIST;
    }
}
