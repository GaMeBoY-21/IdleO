import { describe, expect, it } from "vitest";
import type { components } from "@/api/schema.gen";
import {
  COMMISSION_PERCENT,
  MAX_PRICE,
  barPercent,
  formatCountdown,
  formatPauseEnd,
  machineState,
  pauseChoices,
  stepPrice,
  youKeep,
} from "./hosting";

type Host = components["schemas"]["HostResponse"];

const host = (overrides: Partial<Host> = {}): Host => ({
  id: "01a11300-0000-7000-8000-0000000000a1",
  name: "Nikki's ROG Strix",
  status: "active",
  online: true,
  paused: false,
  updateRequired: false,
  updateWaiting: false,
  createdAt: "2026-10-01T10:00:00Z",
  gpus: [{ model: "NVIDIA GeForce RTX 4080 Laptop GPU", vramMb: 12282, gone: false }],
  ...overrides,
});

// 2026-10-07 10:30 in India is 05:00 UTC.
const MORNING = Date.parse("2026-10-07T05:00:00Z");
const EVENING = Date.parse("2026-10-07T13:30:00Z"); // 7 pm in India

describe("what an owner keeps", () => {
  it("mirrors the control plane's 20% commission", () => {
    expect(COMMISSION_PERCENT).toBe(20);
  });

  it("works out an hour in paise: ₹10.80 an hour keeps ₹8.64 and pays ₹2.16 in fees", () => {
    expect(youKeep(18)).toEqual({ perHour: 1080, keep: 864, fee: 216 });
  });

  it("rounds what the owner keeps down, and the fee takes the rest", () => {
    expect(youKeep(1)).toEqual({ perHour: 60, keep: 48, fee: 12 });
    for (const price of [1, 7, 13, 33, 97, 999]) {
      const { perHour, keep, fee } = youKeep(price);
      expect(keep + fee).toBe(perHour);
      expect(Number.isInteger(keep)).toBe(true);
    }
  });
});

describe("the price stepper", () => {
  it("moves a paisa a minute, ₹0.60 an hour, at a time", () => {
    expect(stepPrice(18, 1)).toBe(19);
    expect(stepPrice(18, -1)).toBe(17);
  });

  it("never goes below 1 or above the most a machine may ask", () => {
    expect(stepPrice(1, -1)).toBe(1);
    expect(stepPrice(MAX_PRICE, 1)).toBe(MAX_PRICE);
  });
});

describe("the pause choices", () => {
  it("offers until I resume, 1 hour, until tonight and until tomorrow morning in the morning", () => {
    const choices = pauseChoices(MORNING);
    expect(choices.map((choice) => choice.id)).toEqual(["resume", "hour", "tonight", "morning"]);
    expect(choices[0]?.until).toBeNull();
    expect(choices[1]?.until).toBe("2026-10-07T06:00:00.000Z");
    // 6 pm in India, 12:30 UTC.
    expect(choices[2]?.until).toBe("2026-10-07T12:30:00.000Z");
    // 8 am in India the next morning, 02:30 UTC.
    expect(choices[3]?.until).toBe("2026-10-08T02:30:00.000Z");
  });

  it("drops tonight once it's past 5 pm in India, and tomorrow morning is still the next day's", () => {
    const choices = pauseChoices(EVENING);
    expect(choices.map((choice) => choice.id)).toEqual(["resume", "hour", "morning"]);
    expect(choices[2]?.until).toBe("2026-10-08T02:30:00.000Z");
  });

  it("treats the small hours as the same morning: at 3 am, 8 am is today", () => {
    const threeAm = Date.parse("2026-10-06T21:30:00Z"); // 3 am on the 7th in India
    expect(pauseChoices(threeAm).find((choice) => choice.id === "morning")?.until).toBe(
      "2026-10-07T02:30:00.000Z",
    );
  });

  it("keeps every choice well inside the API's 7 days", () => {
    for (const now of [MORNING, EVENING]) {
      for (const choice of pauseChoices(now)) {
        if (choice.until) {
          expect(Date.parse(choice.until) - now).toBeLessThan(7 * 24 * 60 * 60_000);
          expect(Date.parse(choice.until)).toBeGreaterThan(now);
        }
      }
    }
  });

  it("says when a pause ends: the time today, and the day beside it otherwise", () => {
    expect(formatPauseEnd("2026-10-07T12:30:00.000Z", MORNING)).toBe("6 pm");
    expect(formatPauseEnd("2026-10-08T03:00:00.000Z", MORNING)).toBe("Thu 8:30 am");
  });
});

describe("the enrolment code's countdown", () => {
  it("counts down in minutes and seconds", () => {
    const expires = "2026-10-07T06:00:00Z";
    expect(formatCountdown(expires, Date.parse("2026-10-07T05:01:48Z"))).toBe("58:12");
    expect(formatCountdown(expires, Date.parse("2026-10-07T05:59:59Z"))).toBe("00:01");
  });

  it("ends at zero", () => {
    expect(formatCountdown("2026-10-07T06:00:00Z", Date.parse("2026-10-07T06:00:00Z"))).toBeNull();
    expect(formatCountdown("2026-10-07T06:00:00Z", Date.parse("2026-10-07T07:00:00Z"))).toBeNull();
  });
});

describe("a machine's state", () => {
  it("is running, with what it has earned so far", () => {
    const state = machineState(
      host({
        currentSession: {
          serviceName: "Fine-tune a model",
          runningLabel: "Fine-tuning",
          startedAt: "2026-10-07T05:00:00Z",
          earnedSoFarPaise: 273,
        },
      }),
      MORNING,
    );
    expect(state).toMatchObject({ kind: "running", active: true });
    expect(state.detail).toBe("Running a session · earning ₹2.73 so far");
  });

  it("is paused until a time, or until the owner resumes", () => {
    expect(machineState(host({ paused: true, pausedUntil: "2026-10-07T12:30:00Z" }), MORNING).detail).toBe(
      "Paused until 6 pm",
    );
    expect(machineState(host({ paused: true }), MORNING)).toMatchObject({
      kind: "paused",
      detail: "Paused until you resume",
      active: false,
    });
  });

  it("finishes a running session when paused, and says so", () => {
    const running = host({
      paused: true,
      currentSession: {
        serviceName: "Run your code",
        runningLabel: "Running your code",
        startedAt: "2026-10-07T05:00:00Z",
        earnedSoFarPaise: 5,
      },
    });
    expect(machineState(running, MORNING).detail).toBe(
      "Running a session · earning ₹0.05 so far · paused after it",
    );
  });

  it("is idle when it's online, active and has nothing to run", () => {
    expect(machineState(host(), MORNING)).toMatchObject({
      kind: "idle",
      detail: "Idle · RTX 4080 Laptop · 12 GB",
      active: true,
    });
  });

  it("is offline, with its GPU when it has reported one", () => {
    expect(machineState(host({ online: false }), MORNING)).toMatchObject({
      kind: "offline",
      detail: "Offline · RTX 4080 Laptop · 12 GB",
    });
    expect(machineState(host({ online: false, gpus: [] }), MORNING).detail).toBe(
      "Offline · waiting for the host app",
    );
  });

  it("needs an update when its agent is too old", () => {
    expect(machineState(host({ updateRequired: true }), MORNING)).toMatchObject({
      kind: "update",
      label: "Update needed",
    });
  });

  it("waits for our checks until an operator activates it, and shows a suspension", () => {
    expect(machineState(host({ status: "pending" }), MORNING).kind).toBe("pending");
    expect(machineState(host({ status: "suspended" }), MORNING).kind).toBe("suspended");
  });
});

describe("the week's bars", () => {
  it("scales each day to the busiest, with a thin bar for a quiet day and none for nothing", () => {
    expect(barPercent(5616, 5616)).toBe(100);
    expect(barPercent(2808, 5616)).toBe(50);
    expect(barPercent(1, 5616)).toBe(4);
    expect(barPercent(0, 5616)).toBe(0);
    expect(barPercent(10, 0)).toBe(0);
  });
});

describe("the machine page's line about the owner's upload", () => {
  it("says the most a job may use, in whole Mbit/s when it is, and what holds before the first check", async () => {
    const { uploadLine } = await import("./hosting");
    expect(uploadLine(25_000)).toBe(
      "Jobs use at most 25 Mbit/s of your upload, so your own internet stays usable.",
    );
    expect(uploadLine(7_500)).toBe(
      "Jobs use at most 7.5 Mbit/s of your upload, so your own internet stays usable.",
    );
    expect(uploadLine(5_000)).toContain("at most 5 Mbit/s");
    for (const unknown of [null, undefined, 0]) {
      expect(uploadLine(unknown)).toBe(
        "Jobs use only a little of your upload until the host app has checked your connection's speed.",
      );
    }
  });
});

describe("the machine page's host app line", () => {
  it("says the version, and whether an update is needed or waiting for an idle moment", async () => {
    const { appLine, releaseLine } = await import("./hosting");
    expect(appLine({ agentVersion: "0.1.0", updateRequired: false, updateWaiting: false })).toBe(
      "host app 0.1.0 · up to date",
    );
    expect(appLine({ agentVersion: "0.1.0", updateRequired: false, updateWaiting: true })).toBe(
      "host app 0.1.0 · updating when idle",
    );
    expect(appLine({ agentVersion: "0.0.9", updateRequired: true, updateWaiting: true })).toBe(
      "host app needs an update",
    );
    expect(appLine({ agentVersion: null, updateRequired: false, updateWaiting: false })).toBe(
      "host app · up to date",
    );
    expect(releaseLine({ version: "0.2.0", setupSizeBytes: 9_800_000 })).toBe("v0.2.0 · 9.8 MB");
  });
});
