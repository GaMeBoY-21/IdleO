import { describe, expect, it } from "vitest";
import { appendChunks, EMPTY_LOG } from "@/api/logs";
import { readSignals, thin } from "./log-signals";

const chunk = (seq: number, text: string) => ({ attempt: 1, seq, text, createdAt: "2026-10-07T06:34:00Z" });

describe("what a job's log says about its progress", () => {
  it("reads the epoch, the step and every loss from the lines the job prints", () => {
    const log = appendChunks(EMPTY_LOG, [
      chunk(1, "loading\nepoch 1/5 step 10/120 loss 2.180\nepoch 1/5 step 20/120 loss 2.160\n"),
      chunk(2, "epoch 2/5 step 30/120 loss=2.1\n"),
    ]);
    expect(readSignals("a", log)).toEqual({
      epoch: { at: 2, of: 5 },
      step: { at: 30, of: 120 },
      losses: [2.18, 2.16, 2.1],
    });
  });

  it("finds nothing in a log with no such lines, and reads only what's new next time", () => {
    const first = appendChunks(EMPTY_LOG, [chunk(1, "hello\nworld\n")]);
    expect(readSignals("b", first)).toEqual({ epoch: null, step: null, losses: [] });
    const more = appendChunks(first, [chunk(2, "step 1/2 loss 0.5\n")]);
    expect(readSignals("b", more).losses).toEqual([0.5]);
    expect(readSignals("b", more).losses).toEqual([0.5]);
  });

  it("thins a long run's losses to a few hundred points, keeping the last", () => {
    const losses = Array.from({ length: 5_000 }, (_, i) => 5_000 - i);
    const thinned = thin(losses);
    expect(thinned.length).toBeLessThan(260);
    expect(thinned.at(-1)).toBe(1);
  });
});
