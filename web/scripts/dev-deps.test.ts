// @vitest-environment node
import { describe, expect, it } from "vitest";
import { packageNamed, packageOf, unnamed } from "./dev-deps.ts";

describe("what a development server optimises up front", () => {
  it("knows a module's package and an import's", () => {
    expect(packageOf("node_modules/.pnpm/uqr@0.1.3/node_modules/uqr/dist/index.mjs")).toBe("uqr");
    expect(
      packageOf(
        "node_modules/.pnpm/@tanstack+react-virtual@3.14.21/node_modules/@tanstack/react-virtual/dist/esm/index.js",
      ),
    ).toBe("@tanstack/react-virtual");
    expect(packageOf("src/features/admin/Table.tsx")).toBeNull();
    expect(packageNamed("zod/mini")).toBe("zod");
    expect(packageNamed("@hookform/resolvers/zod")).toBe("@hookform/resolvers");
  });

  it("asks for a direct dependency only lazily loaded chunks hold, once the main script doesn't", () => {
    const chunks = {
      "assets/shell-abc.js": ["node_modules/.pnpm/react@19/node_modules/react/index.js"],
      "assets/Gate-def.js": [
        "node_modules/.pnpm/uqr@0.1.3/node_modules/uqr/dist/index.mjs",
        "node_modules/.pnpm/react@19/node_modules/react/index.js",
        "node_modules/.pnpm/aria-hidden@1/node_modules/aria-hidden/dist/index.js",
      ],
    };
    const dependencies = new Set(["react", "uqr"]);
    // react is in the main script, and aria-hidden isn't ours to name: a dependency's dependency.
    expect(unnamed(chunks, dependencies, [])).toEqual(["uqr"]);
    expect(unnamed(chunks, dependencies, ["uqr"])).toEqual([]);
  });
});
