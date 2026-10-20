import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { afterPaint } from "./after-first-screen";

/** A PerformanceObserver that reports what a test pushes into it, for the one entry type it is asked about. */
class FakeObserver {
  static supportedEntryTypes = ["paint", "largest-contentful-paint"];
  static watching = new Map<string, (entries: { name: string }[]) => void>();
  private callback: (list: {
    getEntriesByName: (name: string) => unknown[];
    getEntries: () => unknown[];
  }) => void;
  constructor(callback: FakeObserver["callback"]) {
    this.callback = callback;
  }
  observe({ type }: { type: string }) {
    FakeObserver.watching.set(type, (entries) =>
      this.callback({
        getEntriesByName: (name) => entries.filter((entry) => entry.name === name),
        getEntries: () => entries,
      }),
    );
  }
  disconnect() {}
}

describe("afterPaint", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeObserver.watching.clear();
    vi.stubGlobal("PerformanceObserver", FakeObserver);
    vi.spyOn(performance, "getEntriesByName").mockReturnValue([{ name: "first-contentful-paint" } as never]);
    vi.stubGlobal("requestAnimationFrame", (callback: () => void) => setTimeout(callback, 16));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => clearTimeout(id));
    Object.defineProperty(document, "fonts", {
      value: { load: () => Promise.resolve([]) },
      configurable: true,
    });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("runs just after two frames when the first paint has happened already", async () => {
    const work = vi.fn();
    afterPaint(work);
    await vi.advanceTimersByTimeAsync(40);
    expect(work).toHaveBeenCalledTimes(1);
  });

  it("is cancelled by its cancel", async () => {
    const work = vi.fn();
    const cancel = afterPaint(work);
    cancel();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(work).not.toHaveBeenCalled();
  });
});
