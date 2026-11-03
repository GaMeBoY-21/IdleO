import { describe, expect, it } from "vitest";
import { describeGrowth, tidyModuleSizes } from "./main-script-growth";

describe("what a failure of the main script's budget says", () => {
  it("names what grew most first, and what's new, leaving out what didn't grow", () => {
    const said = describeGrowth(
      { "src/App.tsx": 1_000, "src/lib/cn.ts": 100 },
      { "src/App.tsx": 1_200, "src/lib/cn.ts": 90, "date-fns/format.js": 5_000 },
    );
    expect(said.indexOf("date-fns/format.js")).toBeLessThan(said.indexOf("src/App.tsx"));
    expect(said).toContain("+5000 bytes before compression  date-fns/format.js (new in the main script");
    expect(said).toContain("+200 bytes before compression  src/App.tsx");
    expect(said).not.toContain("cn.ts");
  });

  it("says so when no module grew", () => {
    expect(describeGrowth({ "src/App.tsx": 1 }, { "src/App.tsx": 1 })).toContain("vite.config.ts");
  });

  it("gives every machine the same names, and drops what the minifier removed", () => {
    expect(
      tidyModuleSizes({
        "/Users/x/web/src/App.tsx": 10,
        "/home/runner/w/web/node_modules/.pnpm/react@19/node_modules/react/index.js": 20,
        "\0vite/preload-helper.js": 0,
        "/Users/x/web/src/main.tsx?commonjs-entry": 5,
      }),
    ).toEqual({ "react/index.js": 20, "src/App.tsx": 10, "src/main.tsx": 5 });
  });
});
