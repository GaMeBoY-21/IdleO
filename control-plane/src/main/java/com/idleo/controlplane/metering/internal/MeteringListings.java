package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metering's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class MeteringListings {

    @Bean
    AdminListing reviewMinutesListing() {
        return AdminReviewController.LIST;
    }
}
