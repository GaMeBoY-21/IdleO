package com.idleo.controlplane.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The approval engine's settings (ADR 0051). The prod profile refuses cooling-off delays or a refund limit that make it
 * weaker than these defaults (ProductionSettingsGuard).
 *
 * @param refundLimitPaise a refund above this needs a second admin (₹2,000)
 * @param coolingMoney how long a money action waits with no one else to approve it
 * @param coolingAccess how long a role grant, or lifting a ban or a block, waits with no one else to approve it
 * @param expiresAfter how long a request waits for a decision
 * @param downloadFor how long an approved export can be fetched
 * @param sweepEvery how often due requests are run and old ones expired
 */
@ConfigurationProperties("identity.approvals")
record ApprovalProperties(
        @DefaultValue("200000") long refundLimitPaise,
        @DefaultValue("1h") Duration coolingMoney,
        @DefaultValue("24h") Duration coolingAccess,
        @DefaultValue("72h") Duration expiresAfter,
        @DefaultValue("24h") Duration downloadFor,
        @DefaultValue("1m") Duration sweepEvery) {}
