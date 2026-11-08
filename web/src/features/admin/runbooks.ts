/**
 * The runbook behind each screen (ADR 0057), for its `?` panel. A test checks that every screen has one and that the file
 * exists, so a renamed runbook can't leave a dead link.
 */
export const RUNBOOKS: Record<string, { file: string; title: string }> = {
  "/admin/": { file: "stop-the-bleeding.md", title: "Stop the bleeding" },
  "/admin/inbox/": { file: "writing-a-macro.md", title: "Writing a macro" },
  "/admin/users/": { file: "granting-credits.md", title: "Granting credits" },
  "/admin/hosts/": { file: "hosts.md", title: "Hosts" },
  "/admin/fleet/": { file: "rolling-out-an-agent-release.md", title: "Rolling out an agent release" },
  "/admin/sessions/": { file: "admin.md", title: "The admin console" },
  "/admin/review/": { file: "admin.md", title: "The admin console" },
  "/admin/disputes/": { file: "abuse.md", title: "Abuse and reports" },
  "/admin/chargebacks/": { file: "breaching-chargeback.md", title: "A breaching chargeback" },
  "/admin/abuse/": { file: "abuse.md", title: "Abuse and reports" },
  "/admin/appeals/": { file: "handling-an-appeal.md", title: "Handling an appeal" },
  "/admin/images/": { file: "image-refused.md", title: "An image was refused" },
  "/admin/tickets/": { file: "writing-a-macro.md", title: "Writing a macro" },
  "/admin/money/": { file: "payments.md", title: "Payments" },
  "/admin/ledger/": { file: "month-close.md", title: "Closing a month" },
  "/admin/adjustments/": { file: "reopening-a-period.md", title: "Reopening a period" },
  "/admin/months/": { file: "month-close.md", title: "Closing a month" },
  "/admin/money-reports/": { file: "month-close.md", title: "Closing a month" },
  "/admin/settlements/": { file: "settlement-mismatch.md", title: "A settlement mismatch" },
  "/admin/payments/": { file: "payments.md", title: "Payments" },
  "/admin/refunds/": { file: "payments.md", title: "Payments" },
  "/admin/invoices/": { file: "sending-the-ca-pack.md", title: "Sending the CA pack" },
  "/admin/payouts/": { file: "payouts.md", title: "Payouts" },
  "/admin/verification/": { file: "payouts.md", title: "Payouts" },
  "/admin/clawbacks/": { file: "payouts.md", title: "Payouts" },
  "/admin/tax/": { file: "sending-the-ca-pack.md", title: "Sending the CA pack" },
  "/admin/flags/": { file: "maintenance-mode.md", title: "Maintenance mode" },
  "/admin/invites/": { file: "invites.md", title: "Invites" },
  "/admin/notices/": { file: "admin.md", title: "The admin console" },
  "/admin/approvals/": { file: "admin.md", title: "The admin console" },
  "/admin/audit/": { file: "audit-chain.md", title: "The audit chain" },
  "/admin/security/": { file: "admin.md", title: "The admin console" },
};

const REPOSITORY = "https://github.com/GaMeBoY-21/IdleO/blob/main/docs/runbooks";

export const runbookUrl = (file: string) => `${REPOSITORY}/${file}`;

export const HANDBOOK = { file: "admin-handbook.md", title: "The admin handbook: what each role does" };
