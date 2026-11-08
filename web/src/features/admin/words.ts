/**
 * What the console shows for a code (ADR 0057): a state, a ledger account, a reason. No code goes on a screen as it is. A
 * test reads every code the database's own rules allow, so a new one needs its words here in the same change; a code
 * from outside (a bank's reason for a return, Razorpay's for a chargeback) that isn't listed is turned into plain words.
 */
export const WORDS: Record<string, string> = {
  promotions: "Promotions",
  bank: "Bank",
  CANCELLED: "Cancelled",
  COMPLETED: "Completed",
  ENDED_REFUNDED: "Ended and refunded",
  FAILED: "Failed",
  FINISHING: "Finishing",
  HOST_LOST: "Host lost",
  MATCHED: "Matched",
  NO_HOST_FREE: "No host free",
  PROVISIONING: "Starting up",
  REQUESTED: "Waiting for a host",
  RUNNING: "Running",
  agent_only: "Agent only",
  agent_versions: "Agent versions",
  authorized_not_captured: "Authorised, not captured",
  awaiting_confirmation: "Awaiting confirmation",
  captured_not_credited: "Captured, not credited",
  chargeback_losses: "Chargeback losses",
  charged_wrong: "Charged wrongly",
  connection_drops: "Connection drops",
  credit_exhausted: "Credit ran out",
  credit_note: "Credit note",
  didnt_start: "Didn't start",
  disputes_frozen: "Frozen for disputes",
  earnings_forfeited: "Earnings forfeited",
  failed_download: "Download failed",
  failed_verify: "Verification failed",
  fee_correction: "Fee correction",
  free_credit_expired: "Free credit expired",
  gateway_clearing: "Gateway clearing",
  gateway_fees: "Gateway fees",
  goodwill_credit: "Goodwill credit",
  gst_input_credit: "GST input credit",
  gst_on_unused_credit: "GST on unused credit",
  host_misconduct: "Host misconduct",
  host_payable: "Owed to hosts",
  host_receivable: "Owed by hosts",
  host_stopped: "Stopped by the host",
  in_progress: "In progress",
  known_difference: "Known difference",
  late_report: "Late report",
  lease_only: "Lease only",
  max_spend_reached: "Spend limit reached",
  mining_pattern: "Mining pattern",
  missing_ours: "Missing on our side",
  missing_theirs: "Missing on their side",
  name_mismatch: "Name doesn't match",
  not_checked: "Not checked",
  not_refunded: "Not refunded",
  operator_stopped: "Stopped by an operator",
  over_limit: "Over the limit",
  password_cracker: "Password cracker",
  payout_paid: "Payout paid",
  payout_returned: "Payout returned",
  payout_reversed: "Payout reversed",
  payouts_in_flight: "Payouts in flight",
  payouts_paid: "Payouts paid",
  platform_revenue: "Platform revenue",
  pool_lookup: "Mining pool lookup",
  process_hash: "Process hash",
  process_name: "Process name",
  refunds_pending: "Refunds pending",
  renter_purchased: "Bought credit",
  renter_stopped: "Stopped by the renter",
  renter_wallet: "Free credit",
  report_refund: "Report refund",
  rolled_back: "Rolled back",
  settled_later: "Settled later",
  tax_cgst: "CGST",
  tax_igst: "IGST",
  tax_invoice: "Tax invoice",
  tax_sgst: "SGST",
  tds_payable: "TDS payable",
  topped_up: "Topped up",
  unexpected_fee: "Unexpected fee",
  usage_invoice: "Usage invoice",
  write_off: "Write-off",
  write_offs: "Write-offs",
  wrong_output: "Wrong output",
};

/** A code in words; a sentence with codes in it, its codes in words. */
export function word(code: string): string {
  const known = WORDS[code];
  if (known !== undefined) {
    return known;
  }
  if (/^[A-Z0-9]+(_[A-Z0-9]+)*$/.test(code) || /^[a-z0-9]+(_[a-z0-9]+)+$/.test(code)) {
    const spaced = code.replaceAll("_", " ").toLowerCase();
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }
  return code.replace(/\b[a-z0-9]+(?:_[a-z0-9]+)+\b/g, (found) => WORDS[found] ?? found.replaceAll("_", " "));
}
