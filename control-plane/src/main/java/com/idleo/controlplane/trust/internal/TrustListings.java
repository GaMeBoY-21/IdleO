package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Trust's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class TrustListings {

    @Bean
    AdminListing reportsListing() {
        return AdminDisputesController.LIST;
    }

    @Bean
    AdminListing blocklistAdditionsListing() {
        return LearnedBuilds.LIST;
    }

    @Bean
    AdminListing hostFlagsListing() {
        return AdminDisputesController.HOST_FLAGS;
    }

    @Bean
    AdminListing enforcementsListing() {
        return Enforcements.LIST;
    }

    @Bean
    AdminListing appealsListing() {
        return Appeals.LIST;
    }

    @Bean
    AdminListing abuseListing() {
        return AdminAbuseController.LIST;
    }

    @Bean
    AdminListing ratingsListing() {
        return AdminRatingsController.LIST;
    }
}
