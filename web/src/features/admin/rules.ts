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

/** A reason to read a person's data whole: at least 10 characters, or a ticket's number, as the server checks. */
export function revealReasonProblem(reason: string): string | null {
  const text = reason.trim();
  if (text.length < 10 && !/^#?\d+$/.test(text)) {
    return "Say why in at least 10 characters, or give a ticket's number.";
  }
  return text.length > REASON_MAX ? `Keep it to ${REASON_MAX} characters.` : null;
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

/**
 * How long a flag is paused (ADR 0056): until someone runs it again, or for a while, after which it runs again by itself
 * ("off for 2 h"). The server takes the end as a time, and refuses one in the past.
 */
export const PAUSE_CHOICES = [
  { value: "open", label: "Until someone runs it again", minutes: 0 },
  { value: "30m", label: "30 minutes, then it runs again", minutes: 30 },
  { value: "1h", label: "1 hour, then it runs again", minutes: 60 },
  { value: "2h", label: "2 hours, then it runs again", minutes: 120 },
  { value: "4h", label: "4 hours, then it runs again", minutes: 240 },
  { value: "8h", label: "8 hours, then it runs again", minutes: 480 },
] as const;

export type PauseChoice = (typeof PAUSE_CHOICES)[number]["value"];

/** When a pause of `choice` made at `now` ends, or null when it lasts until someone runs it again. */
export function pauseEnd(choice: PauseChoice, now: Date): Date | null {
  const minutes = PAUSE_CHOICES.find((option) => option.value === choice)?.minutes ?? 0;
  return minutes === 0 ? null : new Date(now.getTime() + minutes * 60_000);
}
