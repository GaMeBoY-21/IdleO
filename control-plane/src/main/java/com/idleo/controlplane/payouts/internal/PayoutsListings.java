package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Payouts's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class PayoutsListings {

    @Bean
    AdminListing payoutsListing() {
        return AdminPayoutsController.LIST;
    }
}
