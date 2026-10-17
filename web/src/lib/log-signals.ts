import type { LogState } from "@/api/logs";

/**
 * What the job's own log lines say about its progress (web/DESIGN.md "The session screen"), read from lines the job
 * already prints, never invented: `epoch 4/5`, `step 84/120` and `loss 0.9889`. A job that prints none has no signal,
 * and nothing built from it is drawn.
 */
export interface LogSignals {
  epoch: { at: number; of: number } | null;
  step: { at: number; of: number } | null;
  /** Every loss value in order. */
  losses: number[];
}

const EPOCH = /\bepoch\s+(\d+)\s*\/\s*(\d+)/i;
const STEP = /\bstep\s+(\d+)\s*\/\s*(\d+)/i;
const LOSS = /\bloss[=:\s]\s*(-?\d+(?:\.\d+)?)/i;

export const NO_SIGNALS: LogSignals = { epoch: null, step: null, losses: [] };

/** One more segment's lines folded into the signals. */
function fold(signals: LogSignals, lines: LogState["segments"][number]): LogSignals {
  let { epoch, step } = signals;
  const losses = signals.losses.slice();
  for (const line of lines) {
    if (line.kind !== "line") {
      continue;
    }
    const e = EPOCH.exec(line.text);
    if (e) {
      epoch = { at: Number(e[1]), of: Number(e[2]) };
    }
    const s = STEP.exec(line.text);
    if (s) {
      step = { at: Number(s[1]), of: Number(s[2]) };
    }
    const l = LOSS.exec(line.text);
    if (l) {
      losses.push(Number(l[1]));
    }
  }
  return { epoch, step, losses };
}

const done = new Map<string, { segments: number; signals: LogSignals }>();

/**
 * The signals of a session's log so far, reading only the segments added since last time: a log only grows, and a
 * long one (150,000 lines) is never read twice.
 */
export function readSignals(id: string, log: LogState | undefined): LogSignals {
  if (!log) {
    return NO_SIGNALS;
  }
  const before = done.get(id);
  let { segments, signals } = before ?? { segments: 0, signals: NO_SIGNALS };
  if (log.segments.length < segments) {
    segments = 0;
    signals = NO_SIGNALS;
  }
  if (log.segments.length === segments) {
    return signals;
  }
  for (const segment of log.segments.slice(segments)) {
    signals = fold(signals, segment);
  }
  done.set(id, { segments: log.segments.length, signals });
  return signals;
}

/** At most this many points are drawn: a long run's losses are thinned to every few. */
const MAX_POINTS = 240;

export function thin(values: number[]): number[] {
  if (values.length <= MAX_POINTS) {
    return values;
  }
  const stride = values.length / MAX_POINTS;
  return Array.from({ length: MAX_POINTS }, (_, i) => values[Math.floor(i * stride)]!).concat(values.at(-1)!);
}
