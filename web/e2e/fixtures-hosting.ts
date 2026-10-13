import type { components } from "../src/api/schema.gen";
import { NOW } from "./fixtures";
import { REFUND_SPLIT } from "./fixtures-trust";

type Schemas = components["schemas"];
export type Host = Schemas["HostResponse"];
export type HostSession = Schemas["HostSession"];
export type EarningsEntry = Schemas["EarningsEntry"];
export type OwnerEarnings = Schemas["OwnerEarnings"];
export type Telemetry = Schemas["Telemetry"];

/**
 * The invented data for the hosting screens (ADR 0023), built the way e2e/fixtures.ts builds a renter's: nothing here is
 * typed as a figure of its own. A session's earnings come from its minutes and its machine's price, the way a settlement
 * works them (a host keeps 80% of each 15-minute window, rounded down); every earnings entry is one such window or a
 * giveback; the machines' totals, the week and the days are sums of those entries. src/test/hosting-fixtures.test.ts
 * checks the sums. NOW is Wednesday 7 October 2026, 12:04 in India.
 */
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WINDOW = 15;
const COMMISSION = 20;

const id = (n: number) => `01a11300-0000-7000-8000-${n.toString(16).padStart(12, "0")}`;
const iso = (ms: number) => new Date(ms).toISOString();
const ago = (ms: number) => iso(NOW.getTime() - ms);

/** What a host keeps of an amount: the share a settlement gives it, rounded down. */
export const hostShare = (paise: number) => Math.floor((paise * (100 - COMMISSION)) / 100);

/** What a host earns for `minutes` at `price` a minute, settled in windows of 15 minutes, as metering does. */
export function earnedFor(minutes: number, price: number): number {
  return windows(minutes).reduce((sum, window) => sum + hostShare(window * price), 0);
}

/** 41 minutes settle as 15, 15 and 11. */
export const windows = (minutes: number): number[] => {
  const out: number[] = [];
  for (let left = minutes; left > 0; left -= WINDOW) {
    out.push(Math.min(WINDOW, left));
  }
  return out;
};

export const ROG = { id: id(0xb001), name: "Nikki's ROG Strix", price: 18 };
export const DESKTOP = { id: id(0xb002), name: "Home desktop", price: 12 };
export const GONE = { id: id(0xb003), name: "Old laptop", price: 9 };

type Outcome = "running" | "completed" | "stopped" | "failed" | "lost";
interface Run {
  machine: typeof ROG;
  service: [name: string, running: string];
  startedAgoMs: number;
  minutes: number;
  outcome: Outcome;
  /** For a lost attempt, the minutes given back. */
  givenBackMinutes?: number;
}

const FINE_TUNE: Run["service"] = ["Fine-tune a model", "Fine-tuning"];
const RUN_CODE: Run["service"] = ["Run your code", "Running your code"];

/** Every attempt that ran, newest first. The first is running now, 18 minutes in. */
const runs: Run[] = [
  { machine: ROG, service: FINE_TUNE, startedAgoMs: 18 * MINUTE, minutes: 18, outcome: "running" },
  { machine: ROG, service: FINE_TUNE, startedAgoMs: HOUR + 41 * MINUTE, minutes: 41, outcome: "completed" },
  { machine: DESKTOP, service: FINE_TUNE, startedAgoMs: 20 * HOUR, minutes: 6, outcome: "completed" },
  {
    machine: ROG,
    service: RUN_CODE,
    startedAgoMs: DAY + 20 * HOUR,
    minutes: 30,
    outcome: "lost",
    givenBackMinutes: 4,
  },
  { machine: DESKTOP, service: RUN_CODE, startedAgoMs: 2 * DAY + 3 * HOUR, minutes: 33, outcome: "stopped" },
  { machine: ROG, service: FINE_TUNE, startedAgoMs: 3 * DAY, minutes: 29, outcome: "failed" },
  { machine: DESKTOP, service: RUN_CODE, startedAgoMs: 5 * DAY, minutes: 12, outcome: "completed" },
];

interface Settled {
  run: Run;
  sessionId: string;
  startedAt: string;
  entries: EarningsEntry[];
  givenBackPaise: number;
  earnedPaise: number;
}

let entryNumber = 0xc000;
const settled: Settled[] = runs.map((run, index) => {
  const sessionId = id(0xa000 + index);
  const startedMs = NOW.getTime() - run.startedAgoMs;
  // A running attempt has settled whole windows only; the rest is accrued and in "earned so far".
  const settledMinutes = run.outcome === "running" ? Math.floor(run.minutes / WINDOW) * WINDOW : run.minutes;
  let at = startedMs;
  const entries: EarningsEntry[] = windows(settledMinutes).map((minutes) => {
    at += minutes * MINUTE;
    return {
      id: id(entryNumber++),
      hostId: run.machine.id,
      machineName: run.machine.name,
      kind: "session_earning",
      label: run.service[0],
      serviceName: run.service[0],
      minutes,
      amountPaise: hostShare(minutes * run.machine.price),
      sessionId,
      payoutHeld: false,
      createdAt: iso(at),
    };
  });
  let givenBackPaise = 0;
  if (run.givenBackMinutes) {
    givenBackPaise = hostShare(run.givenBackMinutes * run.machine.price);
    entries.push({
      id: id(entryNumber++),
      hostId: run.machine.id,
      machineName: run.machine.name,
      kind: "lost_minutes_refunded",
      label: "Given back for lost time",
      serviceName: run.service[0],
      minutes: run.givenBackMinutes,
      amountPaise: -givenBackPaise,
      sessionId,
      payoutHeld: false,
      createdAt: iso(at + 3 * MINUTE),
    });
  }
  const credited = entries.filter((e) => e.amountPaise > 0).reduce((sum, e) => sum + e.amountPaise, 0);
  return {
    run,
    sessionId,
    startedAt: iso(startedMs),
    entries,
    givenBackPaise,
    earnedPaise: credited - givenBackPaise,
  };
});

/** What the running attempt has earned so far: its settled windows, and the share of the minutes since. */
const runningRun = settled[0]!;
const accruedMinutes = runningRun.run.minutes % WINDOW;
export const earnedSoFar = runningRun.earnedPaise + hostShare(accruedMinutes * runningRun.run.machine.price);

const telemetryReading = (tempC: number, utilPct: number) => ({
  gpuUuid: "GPU-11111111-2222-3333-4444-555555555555",
  tempC,
  utilPct,
  vramUsedMb: 4_096 + utilPct * 40,
});

/** A GPU's last hour, a point a minute, oldest first: a steady job warming up, deterministic. */
export function telemetryOf(host: Host): Telemetry {
  if (!host.online) {
    return { now: null, lastHour: [] };
  }
  const busy = host.currentSession !== null && host.currentSession !== undefined;
  const lastHour = Array.from({ length: 60 }, (_, index) => ({
    at: iso(NOW.getTime() - (59 - index) * MINUTE),
    gpus: [telemetryReading(busy ? 55 + Math.floor(index / 4) : 38, busy ? 90 + (index % 8) : 3)],
  }));
  return { now: { at: iso(NOW.getTime()), gpus: lastHour.at(-1)!.gpus }, lastHour };
}

const gpu = (model: string, vramMb: number) => ({ model, vramMb, gone: false });
const listing = (price: number, temp = 85) => ({
  batchPricePaisePerMin: price,
  gpuTempCapC: temp,
  maxConcurrentSessions: 1,
  keepFreeGb: 20,
});

const base = (machine: { id: string; name: string; price: number }): Host => ({
  id: machine.id,
  name: machine.name,
  status: "active",
  online: true,
  onlineSince: iso(Date.parse("2026-10-07T04:10:00Z")),
  lastSeenAt: iso(NOW.getTime()),
  paused: false,
  pausedUntil: null,
  agentVersion: "0.1.0",
  updateRequired: false,
  currentSession: null,
  gpus: [gpu("NVIDIA GeForce RTX 4080 Laptop GPU", 12282)],
  listing: listing(machine.price),
  createdAt: "2026-09-20T10:00:00Z",
});

/**
 * What 23 renters rated Nikki's ROG (ADR 0028): the average is the mean of these, to one decimal place, as the control
 * plane works it; a machine needs 5 ratings before one shows.
 */
export const ROG_STARS: number[] = [...Array<number>(16).fill(5), ...Array<number>(5).fill(4), 3, 2];
export const ratingOf = (stars: number[]): NonNullable<Host["rating"]> => ({
  averageStars: Math.round((stars.reduce((sum, star) => sum + star, 0) / stars.length) * 10) / 10,
  count: stars.length,
});

/** Running a session and earning. */
export const hostRunning: Host = {
  ...base(ROG),
  rating: ratingOf(ROG_STARS),
  currentSession: {
    serviceName: runningRun.run.service[0],
    runningLabel: runningRun.run.service[1],
    startedAt: runningRun.startedAt,
    earnedSoFarPaise: earnedSoFar,
  },
};
/** Paused until 6 pm India time: 12:30 UTC. */
export const hostPaused: Host = {
  ...base(DESKTOP),
  gpus: [gpu("NVIDIA GeForce RTX 3060", 12288)],
  paused: true,
  pausedUntil: "2026-10-07T12:30:00.000Z",
};
export const hostIdle: Host = { ...base(ROG), id: id(0xb004), name: "Studio PC" };
export const hostOffline: Host = {
  ...base(DESKTOP),
  id: id(0xb005),
  name: "Spare laptop",
  online: false,
  onlineSince: null,
  lastSeenAt: ago(3 * HOUR),
};
export const hostUpdate: Host = { ...base(ROG), id: id(0xb006), name: "Office PC", updateRequired: true };
/** Just added: connected, with no price yet, and waiting for our checks. */
export const hostNew: Host = {
  ...base(ROG),
  id: id(0xb007),
  name: "My new machine",
  status: "pending",
  listing: null,
  onlineSince: ago(MINUTE),
};
/** Named, and its agent hasn't connected yet. */
export const hostWaiting: Host = {
  ...base(ROG),
  id: id(0xb008),
  name: "My new machine",
  status: "pending",
  online: false,
  onlineSince: null,
  lastSeenAt: null,
  gpus: [],
  listing: null,
};

export const hosts = { hosts: [hostRunning, hostPaused] };
export const noHosts = { hosts: [] };

/** Other hosts with the same GPU charge ₹9.00 to ₹12.00 an hour: 15 to 20 paise a minute (docs/design/hosting/price-phone.html). */
export const priceBand = { lowPaisePerMin: 15, highPaisePerMin: 20, sampleSize: 4 };
export const priceBandDefault = { lowPaisePerMin: 12, highPaisePerMin: 20, sampleSize: 0 };

export const enrolmentCode = {
  code: "K7Q2M-9XMD4-H8TQ3-R5W2N",
  expiresAt: iso(NOW.getTime() + 58 * MINUTE + 12_000),
};

/** What a machine ran, as GET /v1/hosts/{id}/sessions serves it, newest first. */
export function hostSessionsPage(machineId: string): { sessions: HostSession[]; nextCursor: null } {
  return {
    sessions: settled
      .filter((s) => s.run.machine.id === machineId)
      .map((s) => ({
        id: s.sessionId,
        serviceName: s.run.service[0],
        runningLabel: s.run.service[1],
        startedAt: s.startedAt,
        endedAt: s.run.outcome === "running" ? null : iso(Date.parse(s.startedAt) + s.run.minutes * MINUTE),
        minutes: s.run.minutes,
        earnedPaise: s.earnedPaise,
        givenBackPaise: s.givenBackPaise,
        outcome: s.run.outcome,
      })),
    nextCursor: null,
  };
}

/** Every earnings entry, newest first: one per settled window, and each giveback. */
export const earningsEntries: EarningsEntry[] = settled
  .flatMap((s) => s.entries)
  .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));

const IST_OFFSET = 5.5 * HOUR;
const istDate = (ms: number) => new Date(ms + IST_OFFSET).toISOString().slice(0, 10);
const net = (entries: EarningsEntry[]) => entries.reduce((sum, e) => sum + e.amountPaise, 0);

/** The week starts Monday 00:00 India time: Monday 5 October is 4 October 18:30 UTC. */
export const WEEK_STARTS_AT = "2026-10-04T18:30:00.000Z";

const weekEntries = earningsEntries.filter((e) => e.createdAt >= WEEK_STARTS_AT);

/** GET /v1/earnings: every figure a sum of the entries above. */
export const earnings: OwnerEarnings = {
  totalPaise: net(earningsEntries),
  weekStartsAt: WEEK_STARTS_AT,
  weekPaise: net(weekEntries),
  minutesRentedThisWeek: weekEntries.reduce(
    (sum, e) => sum + (e.amountPaise > 0 ? e.minutes : -e.minutes),
    0,
  ),
  days: Array.from({ length: 7 }, (_, index) => {
    const date = istDate(NOW.getTime() - (6 - index) * DAY);
    return {
      date,
      earnedPaise: net(earningsEntries.filter((e) => istDate(Date.parse(e.createdAt)) === date)),
    };
  }),
  machines: [ROG, DESKTOP, GONE].map((machine) => ({
    hostId: machine.id,
    name: machine.name,
    removed: machine === GONE,
    earnedPaise: net(earningsEntries.filter((e) => e.hostId === machine.id)),
  })),
  history: {
    entries: earningsEntries.slice(0, 20),
    nextCursor: earningsEntries.length > 20 ? "page-2" : null,
  },
  payoutsStart: "paid-pilot",
};

/** An owner who has machines and has earned nothing yet. */
export const noEarnings: OwnerEarnings = {
  totalPaise: 0,
  weekStartsAt: WEEK_STARTS_AT,
  weekPaise: 0,
  minutesRentedThisWeek: 0,
  days: earnings.days.map((day) => ({ ...day, earnedPaise: 0 })),
  machines: [],
  history: { entries: [], nextCursor: null },
  payoutsStart: "paid-pilot",
};

/** Every machine above, by id, for a fake API that answers any of them. */
export const allHosts: Host[] = [
  hostRunning,
  hostPaused,
  hostIdle,
  hostOffline,
  hostUpdate,
  hostNew,
  hostWaiting,
];
export const hostById = (hostId: string): Host | undefined => allHosts.find((host) => host.id === hostId);

/**
 * The same earnings after a renter's report was refunded out of this host's share, and with another session's earnings
 * held from payout while a report about it is open (ADR 0028): the refund is a negative entry of its own, labelled
 * "Refunded to renter", and only its host's part of the refund (the commission was spent first); every total is
 * still the sum of the entries.
 */
export function withReportRefund(): OwnerEarnings {
  const refunded = settled.find((s) => s.run.machine === DESKTOP && s.run.outcome === "completed")!;
  const held = settled.find((s) => s.run.outcome === "stopped")!;
  const last = refunded.entries.at(-1)!;
  const refund: EarningsEntry = {
    id: id(0xc900),
    hostId: refunded.run.machine.id,
    machineName: refunded.run.machine.name,
    kind: "refunded_to_renter",
    label: "Refunded to renter",
    serviceName: refunded.run.service[0],
    minutes: 0,
    amountPaise: -REFUND_SPLIT.fromHost,
    sessionId: refunded.sessionId,
    payoutHeld: false,
    createdAt: iso(Date.parse(last.createdAt) + 2 * HOUR),
  };
  const entries = [
    refund,
    ...earningsEntries.map((entry) =>
      entry.sessionId === held.sessionId ? { ...entry, payoutHeld: true } : entry,
    ),
  ].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
  const inWeek = entries.filter((entry) => entry.createdAt >= WEEK_STARTS_AT);
  return {
    ...earnings,
    totalPaise: net(entries),
    weekPaise: net(inWeek),
    minutesRentedThisWeek: inWeek.reduce((sum, e) => sum + (e.amountPaise > 0 ? e.minutes : -e.minutes), 0),
    days: earnings.days.map((day) => ({
      ...day,
      earnedPaise: net(entries.filter((entry) => istDate(Date.parse(entry.createdAt)) === day.date)),
    })),
    machines: earnings.machines.map((machine) => ({
      ...machine,
      earnedPaise: net(entries.filter((entry) => entry.hostId === machine.hostId)),
    })),
    history: { entries: entries.slice(0, 20), nextCursor: entries.length > 20 ? "page-2" : null },
  };
}
