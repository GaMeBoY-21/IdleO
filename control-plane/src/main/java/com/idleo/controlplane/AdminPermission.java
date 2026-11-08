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

    /**
     * The first dashboard's (ADR 0027), which the control room replaced: no endpoint asks for it. It stays while the release
     * before serves, whose roles still name it, and goes with a later release's migration.
     */
    DASHBOARD_READ("dashboard.read", false),
    /** The live dashboard (ADR 0056): the marketplace, supply, funnels and health; money's figures also need money.read. */
    OPS_DASHBOARD_READ("ops.dashboard.read", false),

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
    /** The ledger explorer (ADR 0055): the chart of accounts, balances at any time, every entry and the trial balance. */
    LEDGER_READ("ledger.read", false),
    /** The CA's files and monthly pack (ADR 0055): GST, TDS with whole PANs, the journal; approved, with a factor. */
    TAX_EXPORT("tax.export", true),
    /** Closing a month, and reopening one: the Owner's, with a factor and another admin's approval (ADR 0055). */
    PERIODS_CLOSE("periods.close", true),
    PAYMENTS_READ("payments.read", false),
    RECONCILIATION_RUN("reconciliation.run", false),
    RECONCILIATION_ACCEPT("reconciliation.accept", true),
    /** Razorpay's settlements and how each item matches the ledger (ADR 0055). */
    RECON_READ("recon.read", false),
    /** Resolving a settlement item that doesn't match: by an approved adjustment, or as a known difference. */
    RECON_RESOLVE("recon.resolve", true),
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

    QUEUES_READ("queues.read", false),
    QUEUES_CLAIM("queues.claim", false),
    /** Putting an item on someone else. */
    QUEUES_ASSIGN("queues.assign", false),
    /** The targets the timers run to, some of them legal. */
    QUEUES_SLA("queues.sla", true),
    QUEUES_BULK("queues.bulk", false),
    MACROS_MANAGE("macros.manage", false),

    ENFORCEMENT_READ("enforcement.read", false),
    /** Warning, limiting, suspending or banning: stopping things is immediate, and never waits for approval. */
    ENFORCEMENT_APPLY("enforcement.apply", false),
    /** Lifting any of them, which another admin approves. */
    ENFORCEMENT_LIFT("enforcement.lift", true),
    APPEALS_DECIDE("appeals.decide", false),

    TICKETS_READ("tickets.read", false),
    TICKETS_REPLY("tickets.reply", false),

    INVITES_READ("invites.read", false),
    INVITES_CREATE("invites.create", false),
    NOTICES_PUBLISH("notices.publish", false),

    ADMINS_MANAGE("admins.manage", true),
    APPROVALS_READ("approvals.read", false),
    APPROVALS_DECIDE("approvals.decide", true),
    AUDIT_READ("audit.read", false),
    NOTES_READ("notes.read", false),
    NOTES_WRITE("notes.write", false),
    TAGS_MANAGE("tags.manage", false),
    /** Who might be the same person as whom (ADR 0052): shown to Trust &amp; Safety, and it never acts by itself. */
    TRUST_READ("trust.read", false),
    EXPORT_CREATE("export.create", true),

    /** The kill switches and maintenance mode, and who changed them (ADR 0056). */
    FLAGS_READ("flags.read", false),
    /** Pausing one at once, and asking to run one again, which another admin approves when there is one. */
    FLAGS_CHANGE("flags.change", true);

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
