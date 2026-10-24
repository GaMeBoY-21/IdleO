/**
 * The console's two input rules (ADR 0027), each mirrored from the server so a person learns what's wrong before
 * sending it.
 */

/** A reason is 5 to 500 characters once the spaces around it are removed, as the server's AdminReason checks. */
export const REASON_MIN = 5;
export const REASON_MAX = 500;

/** What's wrong with a reason, or null when it may be sent. */
export function reasonProblem(reason: string): string | null {
  const length = reason.trim().length;
  if (length < REASON_MIN) {
    return `Say why in at least ${REASON_MIN} characters. It goes into the audit log.`;
  }
  if (length > REASON_MAX) {
    return `Keep it to ${REASON_MAX} characters.`;
  }
  return null;
}

/** An authenticator's code: 6 digits, as typed, with spaces or a dash between groups dropped. */
export function totpDigits(typed: string): string {
  return typed.replace(/[\s-]/g, "").replace(/\D/g, "").slice(0, 6);
}

export const isTotp = (typed: string) => /^\d{6}$/.test(typed.replace(/[\s-]/g, ""));

/** A recovery code, like `K7Q2M-9XD4P`: 10 letters and digits, typed in any case, with or without its dash. */
export function recoveryDigits(typed: string): string {
  return typed.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export const isRecoveryCode = (typed: string) => /^[A-HJ-NP-Z2-9]{10}$/.test(recoveryDigits(typed));

/** A refund's amount: rupees and paise as typed, above zero and at most what the session cost; null when it may be sent. */
export function refundProblem(
  paise: number | null,
  mostPaise: number,
  most = "the session cost, less anything given back before",
): string | null {
  if (paise === null || paise <= 0) {
    return "Give an amount above ₹0, in rupees and paise, like 5 or 12.50.";
  }
  if (paise > mostPaise) {
    return `A refund can't be more than ${most}.`;
  }
  return null;
}

/** The minute fields a report's evidence needs, as the usage endpoint gives them. */
export interface WitnessedMinute {
  agentReported: boolean;
  leaseAlive: boolean;
  agentAttempt?: number | null;
  leaseAttempt?: number | null;
  billed: boolean;
  waived: boolean;
}

/** Both witnesses saw it, from the same attempt: the only minute that's ever charged without a person (ADR 0017). */
export const bothWitnesses = (minute: WitnessedMinute) =>
  minute.agentReported && minute.leaseAlive && minute.agentAttempt === minute.leaseAttempt;

/**
 * How a session's minutes stand: how many each witness saw, how many both did and were charged, and which minutes
 * aren't that plain case (one witness only, waived, or not charged), which are the ones worth reading beside a report.
 */
export function witnessSummary<M extends WitnessedMinute>(minutes: M[]) {
  const both = minutes.filter(bothWitnesses);
  return {
    total: minutes.length,
    agent: minutes.filter((minute) => minute.agentReported).length,
    lease: minutes.filter((minute) => minute.leaseAlive).length,
    both: both.length,
    charged: minutes.filter((minute) => minute.billed).length,
    waived: minutes.filter((minute) => minute.waived).length,
    unusual: minutes.filter((minute) => !(bothWitnesses(minute) && minute.billed && !minute.waived)),
  };
}
