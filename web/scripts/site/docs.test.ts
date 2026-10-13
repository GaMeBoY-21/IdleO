// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { publicOperations, loadSpec } from "./openapi";
import { repository, siteContext } from "./context";

const context = siteContext("https://api.example.invalid");
const page = (path: string) => context.docs.find((candidate) => candidate.path === path)!;
const spec = loadSpec(repository("api/openapi.yaml"));

describe("the docs generators", () => {
  it("make every docs page, hand-written and generated", () => {
    expect(context.docs.map((doc) => doc.path)).toEqual([
      "/docs/",
      "/docs/services/",
      "/docs/cli/",
      "/docs/api/",
      "/docs/hosting/",
      "/docs/faq/",
    ]);
    for (const doc of context.docs) {
      expect(doc.html.length, doc.path).toBeGreaterThan(500);
      expect(doc.description.length).toBeGreaterThan(20);
    }
  });

  it("put every operation of the API in the reference, with its path and its method", () => {
    const html = page("/docs/api/").html;
    const operations = publicOperations(spec);
    expect(operations.length).toBeGreaterThan(40);
    for (const operation of operations) {
      expect(html, operation.id).toContain(`id="op-${operation.id}"`);
      expect(html, operation.id).toContain(`<code>${operation.path}</code>`);
    }
  });

  it("leave the admin console's operations out, and say nothing else is missing", () => {
    const all = Object.entries(spec.paths).flatMap(([path, item]) =>
      ["get", "post", "put", "patch", "delete"].filter((method) => method in item).map(() => path),
    );
    const admin = all.filter((path) => path.startsWith("/admin/"));
    expect(admin.length).toBeGreaterThan(0);
    expect(publicOperations(spec)).toHaveLength(all.length - admin.length);
    expect(page("/docs/api/").html).not.toContain("/admin/v1");
  });

  it("describe every schema an operation uses, and link to it", () => {
    const html = page("/docs/api/").html;
    expect(html).toContain('id="schema-SessionResponse"');
    expect(html).toContain('href="#schema-SessionResponse"');
    expect(html).not.toContain('id="schema-AdminDashboard"');
  });

  it("put every command of the command line in its reference", () => {
    const source = readFileSync(repository("cli/src/cli.rs"), "utf8");
    const enumBody = /pub enum Command \{([\s\S]*?)\n\}/.exec(source)![1]!;
    const commands = [...enumBody.matchAll(/^\s{4}([A-Z]\w*)[(,\s]/gm)].map((match) =>
      match[1]!.toLowerCase(),
    );
    expect(commands.length).toBeGreaterThanOrEqual(15);
    const html = page("/docs/cli/").html;
    for (const command of commands.filter((name) => name !== "docs")) {
      expect(html, command).toContain(`<code>idleo ${command}</code>`);
    }
    expect(html).not.toContain("<code>idleo docs</code>");
    expect(html).toContain("<code>idleo run</code>");
    expect(html).toContain("Exit codes");
  });

  it("make the services page from the catalog: what each takes, and none supplying a model", () => {
    const html = page("/docs/services/").html;
    expect(html).toContain("Fine-tune a model");
    expect(html).toContain("Run your code");
    expect(html).toContain("Your model");
    expect(html).toContain("What you bring");
    expect(html).not.toContain("Diagnostics");
    expect(html).toContain("none of these services supplies a model");
  });

  it("give every heading an address to link to", () => {
    for (const doc of context.docs) {
      for (const heading of doc.html.matchAll(/<h([2-4])( [^>]*)?>/g)) {
        expect(heading[2], doc.path).toContain("id=");
      }
    }
  });
});
