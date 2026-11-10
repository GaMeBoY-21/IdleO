import { existsSync, readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/api/errors";
import { savedName } from "./api";
import { HANDBOOK, RUNBOOKS } from "./runbooks";
import { GO_KEYS } from "./Keys";
import { Problem } from "./ui";
import { WORDS, word } from "./words";

// Tests run from web/.
const inRepo = (path: string) => resolve(process.cwd(), "..", path);
const read = (path: string) => readFileSync(inRepo(path), "utf8");

/** Every code the database's own rules allow on a state, status, kind or reason. */
function codesInTheDatabase(): string[] {
  const folder = inRepo("control-plane/src/main/resources/db/migration");
  const codes = new Set<string>();
  for (const file of readdirSync(folder)) {
    const text = readFileSync(resolve(folder, file), "utf8");
    // Each list a rule allows: IN ( ... ), then the quoted values inside it.
    for (const match of text.matchAll(/\bIN\s*\(([^)]*)\)/gi)) {
      for (const value of match[1]!.matchAll(/'([^']+)'/g)) {
        const code = value[1]!;
        if (/^[A-Za-z][A-Za-z0-9_]*$/.test(code) && (code.includes("_") || code === code.toUpperCase())) {
          codes.add(code);
        }
      }
    }
  }
  return [...codes].sort();
}

describe("no raw codes in the console", () => {
  it("every code the database allows has words", () => {
    const codes = codesInTheDatabase();
    expect(codes.length).toBeGreaterThan(50);
    expect(codes.filter((code) => WORDS[code] === undefined)).toEqual([]);
  });

  it("every ledger account has words", () => {
    const accounts = [
      ...read("control-plane/src/main/java/com/idleo/controlplane/billing/internal/Account.java").matchAll(
        /^\s+[A-Z_]+\(\s*"([a-z_]+)"/gm,
      ),
    ].map((match) => match[1]!);
    expect(accounts.length).toBeGreaterThan(5);
    expect(accounts.filter((account) => WORDS[account] === undefined)).toEqual([]);
  });

  it("a code it doesn't know still comes out in words, and a sentence stays a sentence", () => {
    expect(word("goods_or_services_not_received")).toBe("Goods or services not received");
    expect(word("TOO_LITTLE")).toBe("Too little");
    expect(word("NO_HOST_FREE")).toBe("No host free");
    expect(word("The bank said no.")).toBe("The bank said no.");
    expect(word("checks failed: driver_too_old")).toBe("checks failed: driver too old");
    expect(word("weekly")).toBe("weekly");
  });

  it("every problem the API can answer is shown in words, never its type", () => {
    const problems = JSON.parse(read("api/problems.json")) as { slug: string }[];
    const slugs = problems.map((problem) => problem.slug);
    expect(slugs.length).toBeGreaterThan(20);
    for (const slug of slugs) {
      const { container, unmount } = render(
        <Problem error={ApiError.of(400, { type: `/problems/${slug}`, status: 400, title: "Error" })} />,
      );
      expect(container.textContent.length, slug).toBeGreaterThan(0);
      expect(container.textContent, slug).not.toMatch(/[a-z]+-[a-z]+-[a-z]+|\/problems\//);
      unmount();
    }
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("every screen of the console", () => {
  it("has its own page, or opening its address directly gets another screen's and a hydration error", () => {
    const screens = [
      ...read("web/src/features/admin/AdminConsole.tsx").matchAll(/\{ href: "(\/admin\/[^"]*)"/g),
    ].map((match) => match[1]!);
    const pages = read("web/src/screens.ts");
    expect(screens.filter((href) => !pages.includes(`"${href}"`))).toEqual([]);
  });
});

describe("the ? panel", () => {
  const screens = [
    ...read("web/src/features/admin/AdminConsole.tsx").matchAll(/\{ href: "(\/admin\/[^"]*)"/g),
  ].map((match) => match[1]!);

  it("every screen has a runbook, and every runbook exists", () => {
    expect(screens.length).toBeGreaterThan(25);
    expect(screens.filter((href) => RUNBOOKS[href] === undefined)).toEqual([]);
    for (const { file } of [...Object.values(RUNBOOKS), HANDBOOK]) {
      expect(existsSync(inRepo(`docs/runbooks/${file}`)), file).toBe(true);
    }
  });

  it("every g key goes to a screen that exists", () => {
    for (const { href } of Object.values(GO_KEYS)) {
      expect(screens).toContain(href);
    }
  });
});

describe("a fetched file", () => {
  it("is saved under the name the server gave it, never in a folder of its choosing", () => {
    expect(savedName('attachment; filename="idleo-ca-pack-2026-11.zip"')).toBe("idleo-ca-pack-2026-11.zip");
    expect(savedName('attachment; filename="../../etc/passwd"')).toBe("passwd");
    expect(savedName("attachment")).toBeNull();
    expect(savedName(null)).toBeNull();
  });
});
