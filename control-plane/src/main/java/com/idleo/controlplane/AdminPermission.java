package com.idleo.controlplane;

import java.util.Arrays;
import java.util.Locale;

/**
 * What an admin may do, one verb per thing the console's endpoints do (ADR 0051). Every {@code /admin/v1} endpoint
 * declares one with {@link RequiresPermission}; roles, kept in the database, are named bundles of these. A {@code
 * sensitive} permission also needs a factor given in the last five minutes (step-up).
 */
public enum AdminPermission {
    /** What every admin may do with their own access: sign in, see where they stand. Held by all, in no role. */
    SELF("self", false),

    DASHBOARD_READ("dashboard.read", false),

    USERS_READ("users.read", false),
    USERS_PII_REVEAL("users.pii.reveal", true),
    USERS_SUSPEND("users.suspend", false),
    USERS_UNSUSPEND("users.unsuspend", true),

    PRIVACY_READ("privacy.read", false),
    PRIVACY_EXPORT("privacy.export", true),
    PRIVACY_LEGAL_ORDER("privacy.legal-order", true),

    HOSTS_READ("hosts.read", false),
    HOSTS_ACTIVATE("hosts.activate", false),
    HOSTS_SUSPEND("hosts.suspend", false),
    HOSTS_SELF_TEST("hosts.self-test", false),
    HOSTS_CERTIFICATES_REVOKE("hosts.certificates.revoke", false),

    SESSIONS_READ("sessions.read", false),
    SESSIONS_LOG_READ("sessions.log.read", false),
    SESSIONS_STOP("sessions.stop", false),

    REVIEW_READ("review.read", false),
    REVIEW_CHARGE("review.charge", true),
    REVIEW_WAIVE("review.waive", false),

    MONEY_READ("money.read", false),
    MONEY_ADJUST("money.adjust", true),
    PAYMENTS_READ("payments.read", false),
    RECONCILIATION_RUN("reconciliation.run", false),
    RECONCILIATION_ACCEPT("reconciliation.accept", true),
    REFUNDS_READ("refunds.read", false),
    REFUNDS_APPROVE("refunds.approve", true),
    REFUNDS_DECLINE("refunds.decline", false),
    INVOICES_READ("invoices.read", false),
    /** The document itself, with the buyer's name and details whole. */
    INVOICES_PDF("invoices.pdf.read", false),
    INVOICES_CREDIT_NOTE("invoices.credit-note", true),
    CLAWBACKS_READ("clawbacks.read", false),
    CLAWBACKS_WAIVE("clawbacks.waive", true),

    PAYOUTS_READ("payouts.read", false),
    PAYOUTS_RUN("payouts.run", true),
    PAYOUTS_RELEASE("payouts.release", true),
    VERIFICATIONS_READ("verifications.read", false),
    VERIFICATIONS_DECIDE("verifications.decide", false),

    IMAGES_READ("images.read", false),
    IMAGES_BLOCK("images.block", false),
    IMAGES_UNBLOCK("images.unblock", true),

    ABUSE_READ("abuse.read", false),
    ABUSE_DECIDE("abuse.decide", false),
    ABUSE_CLEAR("abuse.clear", true),
    ABUSE_BLOCKLIST("abuse.blocklist", false),

    DISPUTES_READ("disputes.read", false),
    DISPUTES_DECIDE("disputes.decide", false),
    DISPUTES_REFUND("disputes.refund", true),
    RATINGS_READ("ratings.read", false),

    TICKETS_READ("tickets.read", false),
    TICKETS_REPLY("tickets.reply", false),

    INVITES_READ("invites.read", false),
    INVITES_CREATE("invites.create", false),
    NOTICES_PUBLISH("notices.publish", false),

    ADMINS_MANAGE("admins.manage", true),
    APPROVALS_READ("approvals.read", false),
    APPROVALS_DECIDE("approvals.decide", true),
    AUDIT_READ("audit.read", false),
    EXPORT_CREATE("export.create", true);

    private final String key;
    private final boolean sensitive;

    AdminPermission(String key, boolean sensitive) {
        this.key = key;
        this.sensitive = sensitive;
    }

    /** Its name in the database, the API and the console, such as {@code users.pii.reveal}. */
    public String key() {
        return key;
    }

    /** Whether it needs a factor given in the last five minutes. */
    public boolean sensitive() {
        return sensitive;
    }

    public static AdminPermission of(String key) {
        return Arrays.stream(values())
                .filter(permission -> permission.key.equals(key.strip().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such permission: " + key));
    }
}
