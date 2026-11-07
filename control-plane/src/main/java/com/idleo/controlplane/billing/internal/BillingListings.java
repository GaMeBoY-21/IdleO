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
    AdminListing creditGrantsListing() {
        return AdminMoneyController.GRANT_LIST;
    }

    @Bean
    AdminListing ledgerListing() {
        return AdminLedgerController.LEDGER;
    }

    @Bean
    AdminListing settlementsListing() {
        return AdminSettlementsController.LIST;
    }

    @Bean
    AdminListing settlementItemsListing() {
        return AdminSettlementsController.ITEMS;
    }

    @Bean
    AdminListing adjustmentsListing() {
        return AdminAdjustmentsController.LIST;
    }

    @Bean
    AdminListing walletReconciliationListing() {
        return AdminMoneyController.WALLET_RUNS;
    }

    @Bean
    AdminListing paymentMismatchesListing() {
        return PaymentReconciliation.MISMATCHES;
    }

    @Bean
    AdminListing clawbacksListing() {
        return AdminClawbacksController.LIST;
    }
}
