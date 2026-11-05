package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Identity's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class IdentityListings {

    @Bean
    AdminListing usersListing() {
        return AdminUsersController.LIST;
    }

    @Bean
    AdminListing invitesListing() {
        return AdminInvitesController.LIST;
    }
}
