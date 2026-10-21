import { NOW } from "./fixtures";

/**
 * Invented data for Account → Your data and deleting an account (ADR 0037): what the server answers for Asha, whose
 * figures in fixtures.ts these count.
 */
const DAY = 86_400_000;
const ago = (days: number) => new Date(NOW.getTime() - days * DAY).toISOString();
const ahead = (days: number) => new Date(NOW.getTime() + days * DAY).toISOString();

const item = (label: string, count: number, from: number | null, to: number | null) => ({
  label,
  count,
  earliest: from === null ? null : ago(from),
  latest: to === null ? null : ago(to),
});

/** What we hold about Asha, by group. */
export const yourData = () => ({
  groups: [
    {
      group: "account",
      items: [item("API keys", 2, 20, 3), item("account", 1, 36, 0), item("security log entries", 14, 36, 0)],
    },
    { group: "sessions", items: [item("sessions", 6, 30, 0)] },
    { group: "files", items: [item("uploads", 3, 25, 2)] },
    { group: "money", items: [item("ledger lines", 9, 36, 0)] },
    { group: "hosting", items: [item("earnings lines", 0, null, null), item("machines", 0, null, null)] },
    { group: "messages", items: [item("messages", 11, 30, 1)] },
    { group: "reports", items: [item("ratings", 2, 10, 4), item("reports", 1, 9, 9)] },
  ],
});

const exportOf = (state: string, extra: Record<string, unknown> = {}) => ({
  id: "01a11300-0000-7000-8000-0000000e0001",
  state,
  requestedAt: ago(0.01),
  readyAt: null,
  expiresAt: null,
  sizeBytes: null,
  ...extra,
});

export const noExports = () => ({ exports: [] });
export const exportBeingMade = () => ({ exports: [exportOf("requested")] });
export const exportReady = () => ({
  exports: [exportOf("ready", { readyAt: ago(0.005), expiresAt: ahead(7), sizeBytes: 48_213 })],
});
