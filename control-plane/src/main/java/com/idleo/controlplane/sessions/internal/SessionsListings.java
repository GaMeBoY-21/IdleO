package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sessions's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class SessionsListings {

    @Bean
    AdminListing sessionsListing() {
        return AdminSessionsController.LIST;
    }
}
