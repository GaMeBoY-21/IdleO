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
    AdminListing auditListing() {
        return AuditLogController.LIST;
    }

    @Bean
    AdminListing accessLogListing() {
        return AdminPersonalDataController.ACCESS_LOG;
    }

    @Bean
    AdminListing securityEventsListing() {
        return AdminUsersController.SECURITY_EVENTS;
    }

    @Bean
    AdminListing approvalsListing() {
        return ApprovalEngine.LIST;
    }

    @Bean
    AdminListing adminsListing() {
        return AdminRolesController.LIST;
    }

    @Bean
    AdminListing adminSessionsListing() {
        return AdminSessionRecordsController.LIST;
    }

    @Bean
    AdminListing invitesListing() {
        return AdminInvitesController.LIST;
    }
}
