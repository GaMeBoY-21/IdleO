import type { components } from "./schema.gen";

/**
 * All of the account that's kept in this browser (persisted-cache.ts; docs/reference/personal-data-map.md): what the frame
 * draws before anything is fetched, and what decides whether to draw it. Never the whole email address, how they sign in,
 * or anything else a first paint doesn't use: a screen that shows those fetches them once it has painted.
 */
export type KeptMe = {
  displayName: string | null;
  /** The part of the email before the @: the name in the frame, for someone who hasn't given one. */
  emailName: string | null;
  status: string;
  accepted: boolean;
  unreadNotifications: number;
};

export const KEPT_ME_KEY = ["kept-me"] as const;

export const keptMe = (me: components["schemas"]["MeResponse"]): KeptMe => ({
  displayName: me.displayName ?? null,
  emailName: me.email?.split("@")[0] ?? null,
  status: me.status,
  accepted: me.notices.accepted,
  unreadNotifications: me.unreadNotifications,
});
