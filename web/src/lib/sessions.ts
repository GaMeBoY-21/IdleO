import type { Session } from "@/api/queries";

/** What a session has cost so far: what's been charged, plus what's accrued since the last settlement. */
export const costSoFar = (session: Pick<Session, "chargedPaise" | "accruedPaise">) =>
  session.chargedPaise + session.accruedPaise;

/** The templates a renter booked most recently, newest first, each once. */
export function recentTemplates(sessions: Session[], count: number): string[] {
  const seen: string[] = [];
  for (const session of sessions) {
    if (!seen.includes(session.templateId)) {
      seen.push(session.templateId);
    }
  }
  return seen.slice(0, count);
}
