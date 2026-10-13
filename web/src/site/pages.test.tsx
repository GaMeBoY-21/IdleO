// @vitest-environment node
import { describe, expect, it } from "vitest";
import { siteContext } from "../../scripts/site/context";
import { pages, renderPage } from "./render";

const context = siteContext("https://api.example.invalid");
const all = pages(context).map((page) => ({ page, html: renderPage(page) }));
const text = (html: string) =>
  html
    .replace(/<[^>]+>/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/\s+/g, " ");

describe("the public pages", () => {
  it("are the landing page, pricing, GPUs, earn, terms, privacy, contact and every docs page, each once", () => {
    const paths = all.map(({ page }) => page.path);
    expect(new Set(paths).size).toBe(paths.length);
    for (const path of [
      "/about/",
      "/pricing/",
      "/gpus/",
      "/earn/",
      "/terms/",
      "/privacy/",
      "/contact/",
      "/docs/",
    ]) {
      expect(paths).toContain(path);
    }
    expect(paths.filter((path) => path.startsWith("/docs/"))).toHaveLength(context.docs.length);
  });

  it("each has one heading, the header with the five ways on and the footer", () => {
    for (const { page, html } of all) {
      expect(html.match(/<h1[ >]/g), page.path).toHaveLength(1);
      for (const label of [
        "Pricing",
        "GPUs",
        "Earn",
        "Docs",
        "Sign in",
        "Get started",
        "Terms",
        "Privacy",
        "Contact",
      ]) {
        expect(text(html), `${page.path} has ${label}`).toContain(label);
      }
      expect(html).toContain('href="/sign-in/"');
    }
  });

  it("carry a title and a description, and none loads a script of its own in the page's body", () => {
    for (const { page, html } of all) {
      expect(page.title.length).toBeGreaterThan(5);
      expect(page.description.length).toBeGreaterThan(20);
      expect(html).not.toContain("<script");
    }
  });

  it("only the pricing and earn pages have anything live", () => {
    const live = all.filter(({ page }) => page.islands.length > 0).map(({ page }) => page.path);
    expect(live.sort()).toEqual(["/earn/", "/pricing/"]);
  });

  it("never say we supply models, images or transcription, and name no made-up person or number", () => {
    const banned =
      /\b(transcri\w+|ready-made models?|pre-?trained models?|our models|included models|testimonial|trusted by|join \d|\d[\d,]*\+? (happy )?(users|customers))\b/i;
    for (const { page, html } of all) {
      expect(text(html), page.path).not.toMatch(banned);
    }
    expect(text(all.find(({ page }) => page.path === "/about/")!.html)).toContain(
      "You bring your own model, data and code",
    );
  });

  it("say typical wherever the GPUs page says what a card manages", () => {
    const gpus = text(all.find(({ page }) => page.path === "/gpus/")!.html);
    expect(gpus.match(/typical/gi)?.length).toBeGreaterThanOrEqual(16);
  });

  it("leave terms, privacy and contact as placeholders", () => {
    for (const path of ["/terms/", "/privacy/", "/contact/"]) {
      const html = all.find(({ page }) => page.path === path)!.html;
      expect(text(html)).toContain("Being drafted.");
    }
  });

  it("keep the earnings calculator honest about demand", () => {
    expect(text(all.find(({ page }) => page.path === "/earn/")!.html)).toContain(
      "Occupancy depends on demand; early on it may be low.",
    );
  });

  it("draw the pricing rows at one size whether loading or loaded, with the examples from the catalog", () => {
    const html = all.find(({ page }) => page.path === "/pricing/")!.html;
    expect(html.match(/data-class-row="/g)).toHaveLength(4);
    expect(html.match(/data-example=""/g)).toHaveLength(context.examples.length);
    expect(context.examples.length).toBe(2);
    expect(html.match(/class="[^"]*\bh-24\b/g)!.length).toBeGreaterThanOrEqual(6);
  });

  it("name the product only through the one constant, never in a page's own words", async () => {
    const { readdirSync, readFileSync } = await import("node:fs");
    const { join } = await import("node:path");
    const files = (directory: string): string[] =>
      readdirSync(directory, { withFileTypes: true }).flatMap((entry) =>
        entry.isDirectory()
          ? files(join(directory, entry.name))
          : /\.(ts|tsx)$/.test(entry.name) && !/\.test\./.test(entry.name)
            ? [join(directory, entry.name)]
            : [],
      );
    for (const file of files(join(import.meta.dirname))) {
      const code = readFileSync(file, "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/^\s*\/\/.*$/gm, "");
      expect(code, file).not.toMatch(/IdleO/);
    }
  });
});
