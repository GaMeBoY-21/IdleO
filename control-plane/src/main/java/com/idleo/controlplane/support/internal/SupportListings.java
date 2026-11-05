package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Support's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class SupportListings {

    @Bean
    AdminListing ticketsListing() {
        return AdminTicketsController.LIST;
    }
}
