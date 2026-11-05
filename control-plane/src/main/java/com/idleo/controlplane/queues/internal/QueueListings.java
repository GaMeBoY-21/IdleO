package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The inbox's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueueProperties.class)
class QueueListings {

    @Bean
    AdminListing inboxListing() {
        return QueueItems.LIST;
    }

    @Bean
    AdminListing macrosListing() {
        return Macros.LIST;
    }

    @Bean
    AdminListing bulkActionsListing() {
        return BulkActions.LIST;
    }
}
