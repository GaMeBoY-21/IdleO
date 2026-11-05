package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Billing's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class BillingListings {

    @Bean
    AdminListing refundsListing() {
        return AdminRefundsController.LIST;
    }

    @Bean
    AdminListing paymentsListing() {
        return AdminPaymentsController.PAYMENTS;
    }

    @Bean
    AdminListing chargebacksListing() {
        return AdminPaymentsController.CHARGEBACKS;
    }

    @Bean
    AdminListing invoicesListing() {
        return AdminInvoicesController.LIST;
    }

    @Bean
    AdminListing clawbacksListing() {
        return AdminClawbacksController.LIST;
    }
}
