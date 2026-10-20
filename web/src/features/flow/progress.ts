import type { Session } from "@/api/queries";
import { aboutRuntime } from "@/lib/catalog";
import { formatDuration } from "@/lib/time";
import type { Stop } from "./Route";
import { inputsTotal, sessionInputs, type InputFact } from "@/lib/inputs";

const FINDING = new Set(["REQUESTED", "MATCHED"]);
const RUNNING = new Set(["PROVISIONING", "RUNNING", "HOST_LOST", "FINISHING"]);

/**
 * What the route knows besides the session: each of its inputs, with its upload's size once read (src/lib/inputs.ts),
 * and the template's typical runtime.
 */
export interface ProgressExtras {
  /** Every input, in the session's order; the route says what they come to together. */
  inputs?: InputFact[];
  typicalRuntimeSeconds?: number | null;
  /** For "in about 26 min"; from the app's clock. */
  now: number;
}

/** "in 24 s", "in 2 min": how long finding its GPU took, from booking to the first host that took it. */
function foundIn(session: Session): string | null {
  const found = session.events.find((event) => event.state === "MATCHED" || event.state === "PROVISIONING");
  if (!found) {
    return null;
  }
  const seconds = Math.max(0, Math.round((Date.parse(found.at) - Date.parse(session.createdAt)) / 1000));
  return seconds < 90 ? `in ${seconds} s` : `in ${Math.round(seconds / 60)} min`;
}

/** "in about 26 min" while it runs, from the template's typical runtime; null when that isn't known. */
function outputsIn(session: Session, extras: ProgressExtras): string | null {
  if (!session.startedAt || !extras.typicalRuntimeSeconds) {
    return null;
  }
  const left = extras.typicalRuntimeSeconds - (extras.now - Date.parse(session.startedAt)) / 1000;
  return left <= 60 ? "Any minute now" : `In ${aboutRuntime(left)}`;
}

/**
 * A session's progress as a route (docs/design/redesign/live-laptop.html): its data, the host that took it, running
 * with its checkpoints, its outputs. The stop it's at is current; those behind it are done. Home's right panel and
 * the session screen's both draw it.
 */
export function progressStops(session: Session, extras: ProgressExtras): Stop[] {
  const finding = FINDING.has(session.state);
  const running = RUNNING.has(session.state);
  const completed = session.state === "COMPLETED";
  const saved = session.checkpoints.length;
  const found = foundIn(session);
  const given = sessionInputs(session);
  const data: Stop[] =
    given.length > 0
      ? [
          {
            title: "Uploaded",
            detail: (extras.inputs && inputsTotal(extras.inputs)) ?? "Ready",
            state: "done",
          },
        ]
      : [];
  return [
    ...data,
    finding
      ? { title: "Finding your GPU", detail: "Usually under a minute", state: "current" }
      : found === null
        ? { title: "Finding your GPU", detail: "No GPU took it", state: "next" }
        : { title: "GPU found", detail: found, state: "done" },
    {
      title: "Running",
      detail:
        saved > 0
          ? `${saved} checkpoint${saved === 1 ? "" : "s"} ${running ? "so far" : "saved"}`
          : session.startedAt && session.endedAt
            ? `Ran ${formatDuration(session.startedAt, session.endedAt)}`
            : "It saves checkpoints as it runs",
      state: running ? "current" : session.startedAt ? "done" : "next",
    },
    {
      title: "Outputs",
      detail: completed ? "Ready to download" : (outputsIn(session, extras) ?? "When it's done"),
      state: completed ? "done" : "end",
    },
  ];
}
