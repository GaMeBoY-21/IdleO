import type { Session } from "@/api/queries";

/** What a session has cost so far: what's been charged, plus what's accrued since the last settlement. */
export const costSoFar = (session: Pick<Session, "chargedPaise" | "accruedPaise">) =>
  session.chargedPaise + session.accruedPaise;

/** A setup: the template, its settings and its data, which "Book again" offers each once. */
const setupKey = (session: Session) =>
  JSON.stringify([
    session.templateId,
    session.params,
    session.input?.uploadId ?? null,
    session.input?.url ?? null,
  ]);

/** The renter's last distinct setups, newest first. */
export function lastSetups(sessions: Session[], count: number): Session[] {
  const seen = new Set<string>();
  return sessions
    .filter((session) => {
      const key = setupKey(session);
      if (seen.has(key)) {
        return false;
      }
      seen.add(key);
      return true;
    })
    .slice(0, count);
}

/** Book, filled from an earlier session's setup. */
export function bookFrom(session: Pick<Session, "id" | "templateId">): string {
  return `/book/?template=${encodeURIComponent(session.templateId)}&from=${session.id}`;
}
