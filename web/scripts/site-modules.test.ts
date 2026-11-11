// @vitest-environment node
import { describe, expect, it } from "vitest";
import { ownerOf } from "../src/sites/sites.ts";
import { strangers } from "./site-modules.ts";

describe("the code stays apart (ADR 0058)", () => {
  it("knows each part's owner, and that the rest is shared", () => {
    expect(ownerOf("src/features/book/Book.tsx")).toBe("app");
    expect(ownerOf("src/features/hosting/Hosting.tsx")).toBe("host");
    expect(ownerOf("src/features/admin/AdminConsole.tsx")).toBe("admin");
    expect(ownerOf("src/features/upload/upload.ts")).toBeNull();
    expect(ownerOf("src/lib/hosting.ts")).toBeNull();
    expect(ownerOf("node_modules/react/index.js")).toBeNull();
  });

  it("finds renting in the host site, the console in either, and renting or hosting in the admin site", () => {
    const chunks = {
      "assets/shell.js": ["src/main.tsx", "src/features/book/Book.tsx"],
      "assets/AdminConsole.js": ["src/features/admin/AdminConsole.tsx"],
    };
    expect(strangers("host", chunks)).toEqual([
      "assets/shell.js: src/features/book/Book.tsx (app)",
      "assets/AdminConsole.js: src/features/admin/AdminConsole.tsx (admin)",
    ]);
    // The console is the admin site's alone (ADR 0058), even as a chunk of its own the renter app never loads.
    expect(strangers("app", chunks)).toEqual([
      "assets/AdminConsole.js: src/features/admin/AdminConsole.tsx (admin)",
    ]);
    expect(strangers("admin", chunks)).toEqual(["assets/shell.js: src/features/book/Book.tsx (app)"]);
    expect(strangers("app", { "assets/x.js": ["src/features/hosting/Machine.tsx"] })).toEqual([
      "assets/x.js: src/features/hosting/Machine.tsx (host)",
    ]);
  });
});
