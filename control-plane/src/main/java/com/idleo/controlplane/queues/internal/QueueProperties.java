package com.idleo.controlplane.queues.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The inbox's settings (ADR 0053).
 *
 * @param idleClaim how long a claim lasts with no action on the item before it's released
 * @param undoWindow how long a bulk action can be undone
 * @param mostItemsWithoutApproval how many items a bulk action may touch before another admin approves it
 * @param chargebackPageBefore how long before Razorpay's evidence deadline a chargeback pages
 * @param webAppUrl where a macro's link goes
 */
@ConfigurationProperties("queues")
record QueueProperties(
        @DefaultValue("30m") Duration idleClaim,
        @DefaultValue("10m") Duration undoWindow,
        @DefaultValue("20") int mostItemsWithoutApproval,
        @DefaultValue("48h") Duration chargebackPageBefore,
        @DefaultValue("http://localhost:3000") String webAppUrl) {}
