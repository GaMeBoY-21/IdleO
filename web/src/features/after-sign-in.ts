import type { QueryClient } from "@tanstack/react-query";
import { api, data } from "@/api/client";
import { keys } from "@/api/queries";

const WELCOMED_KEY = "idleo.welcomed";
const NEW_ACCOUNT_MS = 10 * 60_000;

/**
 * Where to go once someone has signed in: first the Terms and privacy notice, until the ones in force are agreed to
 * (ADR 0037); then an account that hasn't entered an invite code goes to the invite step (ADR 0024); a brand-new
 * account then sees the one-screen welcome; everyone else goes where they were headed. The first GET /v1/me creates
 * the account on the server.
 */
export async function destinationAfterSignIn(client: QueryClient, next: string | null): Promise<string> {
  const me = await client.query({ queryKey: keys.me, queryFn: () => data(api.GET("/v1/me")) });
  if (!me.notices.accepted) {
    return agreeStep(next);
  }
  if (me.status === "waiting") {
    const to = safeNext(next);
    return to === "/" ? "/invite/" : `/invite/?next=${encodeURIComponent(to)}`;
  }
  const isNew = Date.now() - new Date(me.createdAt).getTime() < NEW_ACCOUNT_MS;
  if (isNew && !wasWelcomed(me.id)) {
    return "/welcome/";
  }
  return safeNext(next);
}

/** The Terms and privacy step, coming back to `next` after. */
export function agreeStep(next: string | null): string {
  const to = safeNext(next);
  return to === "/" ? "/agree/" : `/agree/?next=${encodeURIComponent(to)}`;
}

/** Only a path in this app, never another site. */
export function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export function markWelcomed(userId: string): void {
  try {
    localStorage.setItem(WELCOMED_KEY, userId);
  } catch {
    // A blocked store only means the welcome could show once more.
  }
}

function wasWelcomed(userId: string): boolean {
  try {
    return localStorage.getItem(WELCOMED_KEY) === userId;
  } catch {
    return false;
  }
}
