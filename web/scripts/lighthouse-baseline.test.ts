// @vitest-environment node
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { readBaseline, regressions } from "./lighthouse-baseline";

describe("regressions", () => {
  const baseline = { "home (mobile)": 2000, "book (mobile)": 2500 };

  it("passes a screen that is no more than 10% slower, or faster", () => {
    expect(regressions({ "home (mobile)": 2200, "book (mobile)": 2100 }, baseline)).toEqual([]);
  });

  it("names a screen that is more than 10% slower, with both figures", () => {
    expect(regressions({ "home (mobile)": 2201, "book (mobile)": 2500 }, baseline)).toEqual([
      "home (mobile): 2201 ms, 10% slower than the 2000 ms of the last green run on main (limit 10%)",
    ]);
  });

  it("has nothing to hold a screen to that the baseline doesn't have", () => {
    expect(regressions({ "credit (mobile)": 9000 }, baseline)).toEqual([]);
  });
});

describe("readBaseline", () => {
  const file = (content: string) => {
    const path = join(mkdtempSync(join(tmpdir(), "idleo-baseline-")), "baseline.json");
    writeFileSync(path, content);
    return path;
  };

  it("is null when there is no file yet", () => {
    expect(readBaseline(join(tmpdir(), "idleo-no-such-baseline.json"))).toBeNull();
  });

  it("reads the medians by screen", () => {
    const path = file('{"devtools":{"home (mobile)":2150},"firstVisit":{"credit (mobile)":2400}}');
    expect(readBaseline(path)).toEqual({
      devtools: { "home (mobile)": 2150 },
      firstVisit: { "credit (mobile)": 2400 },
    });
  });

  it("refuses a file that is not a baseline rather than passing everything", () => {
    expect(() => readBaseline(file('{"devtools":{"home (mobile)":"slow"},"firstVisit":{}}'))).toThrow(
      /not a Lighthouse baseline/,
    );
    expect(() => readBaseline(file("{"))).toThrow();
  });
});
