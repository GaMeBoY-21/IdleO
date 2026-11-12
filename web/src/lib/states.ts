/**
 * The one map from a state to its chip (web/DESIGN.md "States and chips"): for each kind of thing, each code's words and
 * its tone. `StatusChip` draws the tone as a colour and an icon, so a state is never shown by colour alone. Every screen
 * of every site shows a state through here; a code the map doesn't know yet is shown in plain words, neutral.
 *
 * A session's words are the renter's ("Finding your GPU" for both REQUESTED and MATCHED); `exact` is the console's,
 * where the difference matters.
 */
import type { ConsoleStateDomain } from "@/features/admin/states";

export type Tone = "live" | "success" | "warning" | "danger" | "neutral";
export interface StateWord {
  label: string;
  tone: Tone;
  /** The console's words, where they're more exact than the renter's. */
  exact?: string;
}

const SESSION: Record<string, StateWord> = {
  REQUESTED: { label: "Finding your GPU", tone: "live", exact: "Waiting for a host" },
  MATCHED: { label: "Finding your GPU", tone: "live", exact: "Matched" },
  PROVISIONING: { label: "Starting", tone: "live" },
  RUNNING: { label: "Running", tone: "live" },
  HOST_LOST: { label: "Moving to another GPU", tone: "warning", exact: "Host lost" },
  FINISHING: { label: "Saving", tone: "live", exact: "Finishing" },
  COMPLETED: { label: "Done", tone: "success", exact: "Completed" },
  FAILED: { label: "Failed", tone: "danger" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  NO_HOST_FREE: { label: "No GPU was free", tone: "neutral", exact: "No host free" },
  ENDED_REFUNDED: { label: "Ended and refunded", tone: "neutral" },
};

/** A host's owner's machine (src/lib/hosting.ts, `MachineKind`); its label there says more ("Paused until 6 pm"). */
const MACHINE: Record<string, StateWord> = {
  running: { label: "Running a session", tone: "live" },
  idle: { label: "Idle", tone: "neutral" },
  paused: { label: "Paused", tone: "warning" },
  update: { label: "Update needed", tone: "warning" },
  suspended: { label: "Suspended", tone: "danger" },
  unknown: { label: "Status unknown", tone: "neutral" },
  offline: { label: "Offline", tone: "neutral" },
  pending: { label: "Checking", tone: "neutral" },
};

const APP_DOMAINS = {
  session: SESSION,
  machine: MACHINE,
  /** A renter's own image (ADR 0042). */
  image: {
    ready: { label: "Ready", tone: "success" },
    scanning: { label: "Being checked", tone: "live" },
    downloading: { label: "Downloading", tone: "live" },
    blocked: { label: "Blocked", tone: "danger" },
    failed: { label: "Check failed", tone: "danger" },
    removed: { label: "Removed", tone: "neutral" },
  },
} satisfies Record<string, Record<string, StateWord>>;

/** The console's domains are its own chunk's (features/admin/states.ts), added to the map when the console loads. */
export type StateDomain = keyof typeof APP_DOMAINS | ConsoleStateDomain;

const DOMAINS: Record<string, Readonly<Record<string, StateWord>> | undefined> = { ...APP_DOMAINS };

/** Adds a site's own domains to the one map: the console's, from its chunk, so the renter's first load doesn't carry them. */
export function addStates(domains: Readonly<Record<string, Readonly<Record<string, StateWord>>>>): void {
  Object.assign(DOMAINS, domains);
}

/** A code in plain words, for one the map doesn't know yet: "NO_HOST_FREE" is "No host free". */
export const readable = (code: string) => {
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

export function stateOf(domain: StateDomain, code: string): StateWord {
  return DOMAINS[domain]?.[code] ?? { label: readable(code), tone: "neutral" };
}

/** Every code each domain knows so far, for the gallery and the tests. */
export const stateDomains = () => DOMAINS;
