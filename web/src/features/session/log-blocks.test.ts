import { describe, expect, it } from "vitest";
import type { LogLine } from "@/api/logs";
import { blocks, type LogBlock } from "./LogPanel";

const lines = (from: number, to: number): LogLine[] =>
  Array.from({ length: to - from }, (_, i) => ({ kind: "line", text: `line ${from + i}` }));

describe("the log panel's blocks", () => {
  it("keeps a closed block as the same object on an append, so it's neither joined nor drawn again", () => {
    const drawn = new Map<string, LogBlock>();
    const first = blocks(lines(0, 250), 0, drawn);
    const second = blocks(lines(0, 260), 0, drawn);
    expect(first.map((block) => block.key)).toEqual(["l0", "l100", "l200"]);
    expect(second[0]).toBe(first[0]);
    expect(second[1]).toBe(first[1]);
    // The last block is still growing: it's rebuilt, with its new lines.
    expect(second[2]).not.toBe(first[2]);
    expect(second[2]).toMatchObject({ key: "l200", count: 60 });
  });

  it("never keeps a block whose last line was half written, even when it closes at the same count", () => {
    const drawn = new Map<string, LogBlock>();
    const half: LogLine[] = [...lines(0, 99), { kind: "line", text: "epo" }];
    blocks(half, 0, drawn);
    const whole: LogLine[] = [
      ...lines(0, 99),
      { kind: "line", text: "epoch 2" },
      { kind: "line", text: "next" },
    ];
    const after = blocks(whole, 0, drawn);
    expect(after[0]).toMatchObject({ key: "l0", count: 100 });
    expect(after[0]!.kind === "lines" && after[0]!.text.endsWith("epoch 2")).toBe(true);
  });

  it("keeps blocks by key as the window moves, and lets go of the ones that left it", () => {
    const drawn = new Map<string, LogBlock>();
    const before = blocks(lines(0, 2_000), 0, drawn);
    const moved = blocks(lines(100, 2_100), 100, drawn);
    expect(moved[0]).toBe(before[1]);
    expect(drawn.has("l0")).toBe(false);
  });
});
