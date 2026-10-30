import type { components } from "@/api/schema.gen";
import { formatPaise } from "@/lib/money";

type Host = components["schemas"]["HostResponse"];

/**
 * What an owner works out on their own screens (ADR 0023), all in integer paise: what they keep of a price, the
 * pause choices as times in India, the countdown of an enrolment code and how a machine's state reads.
 */

/**
 * The platform's share of what a session is charged, in percent. It mirrors the control plane's
 * `billing.commission-percent` (application.yaml), the way Book's GPU memory rule mirrors the server's (vram.ts): the
 * API doesn't send it, and src/lib/hosting.test.ts pins it. The host's share is rounded down, as a settlement rounds it.
 */
export const COMMISSION_PERCENT = 20;

/** The price a host may ask, in paise a minute: at least 1 (₹0.60 an hour), at most 1,000 (₹600 an hour). */
export const MIN_PRICE = 1;
export const MAX_PRICE = 1_000;

/** One step of the price: 1 paisa a minute, ₹0.60 an hour, which is why prices move in whole steps. */
export const stepPrice = (price: number, direction: 1 | -1): number =>
  Math.min(MAX_PRICE, Math.max(MIN_PRICE, price + direction));

/** What a price pays the host in an hour, what they keep of it and the platform's fee: paise, never fractions. */
export function youKeep(pricePaisePerMin: number): { perHour: number; keep: number; fee: number } {
  const perHour = pricePaisePerMin * 60;
  const keep = Math.floor((perHour * (100 - COMMISSION_PERCENT)) / 100);
  return { perHour, keep, fee: perHour - keep };
}

/**
 * What renters pay in an hour for a price (ADR 0039): the price, plus GST at `gstPercent` once the platform charges it,
 * rounded half up to the paisa as the control plane's `Gst.taxOn` does. The host keeps a share of the price before GST
 * (youKeep), so turning GST on changes what renters pay and never what the host earns.
 */
export function renterPays(pricePaisePerMin: number, gstPercent: number): number {
  const perHour = pricePaisePerMin * 60;
  return perHour + (gstPercent > 0 ? Math.floor((perHour * gstPercent + 50) / 100) : 0);
}

/**
 * What the self-test checks, in the words an owner reads, in the order the app runs them. A requested run shows all of
 * these from the start, each waiting until its result arrives, so the list never grows under the person's eyes.
 */
export const SELF_TEST_CHECKS: ReadonlyArray<readonly [name: string, label: string]> = [
  ["distro_registered", "IdleO's Linux is installed"],
  ["lockdown_config", "Its settings keep jobs away from your files"],
  ["gpu_visible", "Your GPU is visible inside it"],
  ["container_runtime", "Containers can start"],
  ["runner", "The job runner is ready"],
  ["scratch_encryption", "Job disks are encrypted"],
  ["gpu_in_container", "A job can use your GPU"],
  ["lockdown", "Jobs can't reach your computer"],
  ["network_lockdown", "Jobs can't reach your home network"],
  ["disk", "There's enough free disk"],
  ["storage_speed", "The line to IdleO's storage is fast enough"],
];

/** Why the app wouldn't run a requested self-test, from its code. */
export function selfTestRefusal(code: string | null | undefined): string {
  return code === "session_running"
    ? "A renter's session started on this machine, so the app didn't run it."
    : "The app was already running one.";
}

/**
 * How reliable a machine has been, in a word and what holds it down (the control plane's host score). `lowers` comes
 * from the server as kinds; a count is how many in the last 30 days. Never says who reported, or that an admin did.
 */
export function reliabilityWord(label: Host["reliability"]["label"]): string {
  return label === "good" ? "Good" : label === "fair" ? "Fair" : "New";
}

export function reliabilityLowers(lowers: Host["reliability"]["lowers"]): string[] {
  return lowers.map((entry) => {
    const count = entry.count ?? 0;
    switch (entry.kind) {
      case "sessions_lost":
        return count === 1
          ? "1 session was lost here in the last 30 days (the app went offline or stopped)."
          : `${count} sessions were lost here in the last 30 days (the app went offline or stopped).`;
      case "self_tests_failed":
        return count === 1
          ? "1 self-test in the last 30 days had a check that failed."
          : `${count} self-tests in the last 30 days had a check that failed.`;
      default:
        return "Renters' reports about sessions here counted against it.";
    }
  });
}

/** The temperature limit's range, as the API takes it. */
export const TEMP_MIN = 60;
export const TEMP_MAX = 95;
/** The disk kept free for the owner, in GB, as the API takes it. */
export const KEEP_FREE_MIN = 10;
export const KEEP_FREE_MAX = 500;

const IST_OFFSET_MS = 5.5 * 60 * 60_000;
const HOUR_MS = 60 * 60_000;
const DAY_MS = 24 * HOUR_MS;
const TONIGHT_HOUR = 18;
const MORNING_HOUR = 8;

/** The moment of `hour` o'clock in India on the calendar day `days` after the one `now` falls on there. */
function istAt(now: number, days: number, hour: number): number {
  const istNow = now + IST_OFFSET_MS;
  const midnight = istNow - (((istNow % DAY_MS) + DAY_MS) % DAY_MS);
  return midnight + days * DAY_MS + hour * HOUR_MS - IST_OFFSET_MS;
}

export interface PauseChoice {
  id: "resume" | "hour" | "tonight" | "morning";
  label: string;
  /** When the pause ends, as the API takes it; null to pause until the owner resumes. */
  until: string | null;
}

/**
 * How long a pause may last (web/DESIGN.md "Hosting"): until I resume, 1 hour, until tonight (6 pm in India; not
 * offered once it's past) and until tomorrow morning (8 am). Each is well inside the API's 7 days.
 */
export function pauseChoices(now: number): PauseChoice[] {
  const choices: PauseChoice[] = [{ id: "resume", label: "Until I resume", until: null }];
  choices.push({ id: "hour", label: "1 hour", until: new Date(now + HOUR_MS).toISOString() });
  const tonight = istAt(now, 0, TONIGHT_HOUR);
  if (tonight > now + HOUR_MS) {
    choices.push({ id: "tonight", label: "Until tonight", until: new Date(tonight).toISOString() });
  }
  const morning = istAt(now, 0, MORNING_HOUR);
  choices.push({
    id: "morning",
    label: "Until tomorrow morning",
    until: new Date(morning > now + HOUR_MS ? morning : istAt(now, 1, MORNING_HOUR)).toISOString(),
  });
  return choices;
}

const clock = new Intl.DateTimeFormat("en-IN", {
  hour: "numeric",
  minute: "2-digit",
  hour12: true,
  timeZone: "Asia/Kolkata",
});
const weekday = new Intl.DateTimeFormat("en-IN", { weekday: "short", timeZone: "Asia/Kolkata" });

/** "6 pm", "8:30 am", and with the day when it isn't today in India: "Wed 8 am". */
export function formatPauseEnd(until: string, now: number): string {
  const time = clock.format(new Date(until)).replace(":00", "").toLowerCase();
  const sameDay = istAt(now, 0, 0) === istAt(new Date(until).getTime(), 0, 0);
  return sameDay ? time : `${weekday.format(new Date(until))} ${time}`;
}

/** An enrolment code's countdown, "58:12", or null once it has expired. */
export function formatCountdown(expiresAt: string, now: number): string | null {
  const seconds = Math.floor((new Date(expiresAt).getTime() - now) / 1000);
  if (seconds <= 0) {
    return null;
  }
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

export type MachineKind =
  "running" | "paused" | "update" | "unknown" | "offline" | "pending" | "suspended" | "idle";

export interface MachineState {
  kind: MachineKind;
  /** A word or two: the state chip. */
  label: string;
  /** The line under the machine's name. */
  detail: string;
  /** Whether the machine is earning or can be offered work now: its dot is lit. */
  active: boolean;
}

/**
 * A machine's state in words (web/DESIGN.md "Hosting"). One state at a time: a session that's running comes first
 * (a paused machine finishes it), then a pause, then an agent that must update, then a status we can't read just now
 * (never shown as offline: the laptop is likely fine), then offline, then a machine that hasn't been activated, a
 * suspended one, and at last idle.
 */
export function machineState(host: Host, now: number): MachineState {
  const gpu = host.gpus.find((candidate) => !candidate.gone);
  if (host.currentSession) {
    return {
      kind: "running",
      label: "Running a session",
      detail: `Running a session · earning ${formatPaise(host.currentSession.earnedSoFarPaise)} so far${
        host.paused ? " · paused after it" : ""
      }`,
      active: true,
    };
  }
  if (host.paused) {
    const until = host.pausedUntil ? formatPauseEnd(host.pausedUntil, now) : null;
    return {
      kind: "paused",
      label: "Paused",
      detail: until ? `Paused until ${until}` : "Paused until you resume",
      active: false,
    };
  }
  if (host.updateRequired) {
    return {
      kind: "update",
      label: "Update needed",
      detail: "Update the host app to get work",
      active: false,
    };
  }
  if (host.status === "suspended") {
    return { kind: "suspended", label: "Suspended", detail: "Suspended by IdleO. Contact us", active: false };
  }
  if (host.presence === "UNKNOWN") {
    return { kind: "unknown", label: "Status unknown", detail: "Status unknown, checking…", active: false };
  }
  if (!host.online) {
    return {
      kind: "offline",
      label: "Offline",
      detail: gpu ? `Offline · ${gpuLine(gpu)}` : "Offline · waiting for the host app",
      active: false,
    };
  }
  if (host.status !== "active") {
    return { kind: "pending", label: "Checking", detail: "Online · waiting for our checks", active: false };
  }
  return { kind: "idle", label: "Idle", detail: gpu ? `Idle · ${gpuLine(gpu)}` : "Idle", active: true };
}

function gpuLine(gpu: { model: string; vramMb: number }): string {
  const name = gpu.model
    .replace(/^NVIDIA\s+/i, "")
    .replace(/^GeForce\s+/i, "")
    .replace(/\s+GPU$/i, "");
  return `${name} · ${Math.round(gpu.vramMb / 1024)} GB`;
}

/** A day's bar in the chart: its height as a share of the busiest day, 0 to 100, whole percent. */
export function barPercent(earned: number, busiest: number): number {
  if (busiest <= 0 || earned <= 0) {
    return 0;
  }
  return Math.max(4, Math.round((earned * 100) / busiest));
}

/** A price as the owner reads it: paise a minute as rupees an hour, "₹10.80". */
export const rupeesAnHour = (pricePaisePerMin: number): string => formatPaise(pricePaisePerMin * 60);

/** "41 min", "2 h 04 min", "1 h": minutes as a person reads them, whole, never seconds. */
export function formatMinutes(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const rest = minutes % 60;
  return rest === 0
    ? `${Math.floor(minutes / 60)} h`
    : `${Math.floor(minutes / 60)} h ${String(rest).padStart(2, "0")} min`;
}

type HostSession = components["schemas"]["HostSession"];

/** How an attempt on a machine ended, in an owner's words, and what to expect from the money. */
export function outcomeLabel(session: Pick<HostSession, "outcome" | "givenBackPaise">): string {
  switch (session.outcome) {
    case "running":
      return "Running";
    case "completed":
      return "Completed";
    case "stopped":
      return "Stopped by the renter";
    case "failed":
      return "Failed";
    case "lost":
      return session.givenBackPaise > 0
        ? `Lost, ${formatPaise(session.givenBackPaise)} given back`
        : "Lost, nothing to give back";
    default:
      return session.outcome.charAt(0).toUpperCase() + session.outcome.slice(1);
  }
}

/**
 * The machine page's words for its host app (ADR 0034): its version, and whether it needs an update to get work, or
 * has one waiting that it installs when it's idle.
 */
export function appLine(host: Pick<Host, "agentVersion" | "updateRequired" | "updateWaiting">): string {
  if (host.updateRequired) {
    return "host app needs an update";
  }
  const version = host.agentVersion ? `host app ${host.agentVersion}` : "host app";
  return host.updateWaiting ? `${version} · updating when idle` : `${version} · up to date`;
}

/**
 * What the machine page tells its owner about their internet (ADR 0038): the most a job may upload at once, half of what
 * the host app measured the line to be, never under its floor ("Jobs use at most 25 Mbit/s of your upload"); before the
 * first speed check it says so, as the host app uses its floor until then.
 */
export function uploadLine(uploadCapKbps: number | null | undefined): string {
  if (!uploadCapKbps) {
    return "Jobs use only a little of your upload until the host app has checked your connection's speed.";
  }
  const mbit = uploadCapKbps / 1_000;
  return `Jobs use at most ${Number.isInteger(mbit) ? mbit : mbit.toFixed(1)} Mbit/s of your upload, so your own internet stays usable.`;
}

/** "v0.2.0 · 9.8 MB": the Download button's version and the setup program's size. */
export function releaseLine(release: { version: string; setupSizeBytes: number }): string {
  return `v${release.version} · ${(release.setupSizeBytes / 1_000_000).toFixed(1)} MB`;
}
