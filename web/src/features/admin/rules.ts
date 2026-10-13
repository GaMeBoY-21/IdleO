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
