/** Where each kind of record opens: one address for one thing, wherever its id appears in the console (ADR 0052). */
const PAGES: Record<string, string> = {
  user: "/admin/users/",
  host: "/admin/hosts/",
  session: "/admin/sessions/",
  payment: "/admin/payments/",
  refund: "/admin/refunds/",
  invoice: "/admin/invoices/",
  payout: "/admin/payouts/",
  ticket: "/admin/tickets/",
  image: "/admin/images/",
  report: "/admin/disputes/",
  abuse: "/admin/abuse/",
  chargeback: "/admin/chargebacks/",
  settlement: "/admin/settlements/",
  adjustment: "/admin/adjustments/",
};

export type RecordPage = keyof typeof PAGES;

export function recordHref(page: string, id: string): string {
  return `${PAGES[page] ?? "/admin/"}?id=${encodeURIComponent(id)}`;
}
