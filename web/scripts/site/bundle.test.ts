// @vitest-environment node
import { spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * The app's code is never loaded on a public page (ADR 0029): the public site is its own build, so its scripts hold only
 * what src/site/ imports, and the sources may import only the few plain modules the app shares with it.
 */
const web = fileURLToPath(new URL("../../", import.meta.url));
const out = mkdtempSync(join(tmpdir(), "idleo-site-"));
const build = spawnSync(
  join(web, "node_modules/.bin/vite"),
  ["build", "--config", "vite.site.config.ts", "--outDir", out, "--logLevel", "error"],
  { cwd: web, encoding: "utf8" },
);

const files = (directory: string): string[] =>
  readdirSync(directory, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? files(join(directory, entry.name)) : [join(directory, entry.name)],
  );

/** What a script of the public site may import from the app: money, the host's share, the class names, the brand. */
const SHARED = [
  "@/lib/money",
  "@/lib/hosting",
  "@/lib/cn",
  "@/brand/brand",
  "@/brand/Wordmark",
  // What real visits meet, reported by every page after it has loaded (ADR 0036).
  "@/telemetry/early-errors",
  "@/telemetry/report",
  "@/telemetry/privacy",
];

describe("the public site's bundle", () => {
  it("builds", () => {
    expect(build.status, build.stderr).toBe(0);
  });

  it("holds none of the app: no React, query cache, router, sign-in, or any call but the public availability, the Windows app's release and the vitals", () => {
    const scripts = files(out).filter((file) => file.endsWith(".js"));
    expect(scripts.length).toBeGreaterThanOrEqual(3);
    for (const script of scripts) {
      const code = readFileSync(script, "utf8");
      for (const marker of [
        "react",
        "useState",
        "QueryClient",
        "wouter",
        "firebase",
        "oidc",
        "idb-keyval",
        "openapi-fetch",
        "Authorization",
        "/admin/",
      ]) {
        expect(code, `${relative(out, script)} has ${marker}`).not.toContain(marker);
      }
      for (const call of code.matchAll(/\/v1\/[\w/-]*/g)) {
        expect(
          ["/v1/public/availability", "/v1/agent-releases/latest", "/v1/vitals"],
          relative(out, script),
        ).toContain(call[0]);
      }
    }
  });

  it("is small: every script and the style sheet together are a few kilobytes", () => {
    const total = files(out)
      .filter((file) => /\.(js|css)$/.test(file))
      .reduce((sum, file) => sum + readFileSync(file).length, 0);
    expect(total).toBeLessThan(60_000);
  });

  it("is written only from what src/site/ imports, and the plain modules it shares", () => {
    const sources = files(join(web, "src/site")).filter(
      (file) => /\.(ts|tsx|css)$/.test(file) && !/\.test\./.test(file),
    );
    for (const source of sources) {
      const code = readFileSync(source, "utf8");
      for (const imported of code.matchAll(/(?:from|import)\s+["']([^"']+)["']/g)) {
        const target = imported[1]!;
        const allowed =
          target.startsWith(".") ||
          target.startsWith("react-dom/server") ||
          target === "react" ||
          SHARED.includes(target) ||
          // the type of the shared money helper's own dependencies
          target === "tailwindcss";
        expect(allowed, `${relative(web, source)} imports ${target}`).toBe(true);
      }
      // A relative import must stay inside the site's own folder.
      for (const imported of code.matchAll(/from\s+["'](\.[^"']*)["']/g)) {
        const resolved = join(dirname(source), imported[1]!);
        expect(
          resolved.startsWith(join(web, "src/site")) || resolved.startsWith(join(web, "src/brand")),
          `${source} imports ${imported[1]}`,
        ).toBe(true);
      }
    }
  });
});
