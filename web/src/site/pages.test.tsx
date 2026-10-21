// @vitest-environment node
import { describe, expect, it } from "vitest";
import { siteContext } from "../../scripts/site/context";
import { pages, renderPage } from "./render";

const APP = "https://app.example.invalid";
const context = siteContext("https://api.example.invalid", APP);
const all = pages(context).map((page) => ({ page, html: renderPage(page, context.links) }));
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
      "/",
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

  it("start hosting in the app's Hosting tab, and the landing page is the website's own front door", () => {
    expect(all.find(({ page }) => page.path === "/earn/")!.html).toContain(`href="${APP}/hosting/"`);
    expect(all.find(({ page }) => page.path === "/")!.html).toContain('aria-label="IdleO home"');
    expect(all.some(({ page }) => page.path === "/about/")).toBe(false);
  });

  it("each has one heading, the header with the five ways on and the footer", () => {
    for (const { page, html } of all) {
      expect(html.match(/<h1[ >]/g), page.path).toHaveLength(1);
      for (const label of [
        "Pricing",
        "GPUs",
        "Earn",
        "Docs",
        "Log in",
        "Get started",
        "Terms",
        "Privacy",
        "Contact",
      ]) {
        expect(text(html), `${page.path} has ${label}`).toContain(label);
      }
      // Every way into the app leads to the app's own address, never to a path on the website.
      expect(html).toContain(`href="${APP}/sign-in/"`);
      expect(html).toContain(`href="${APP}/sign-in/?start=1"`.replace("&", "&amp;"));
      expect(html.match(/href="\/(sign-in|hosting|book|credit|account)\/[^"]*"/g) ?? [], page.path).toEqual(
        [],
      );
    }
  });

  it("carry a title and a description, and none loads a script of its own in the page's body", () => {
    for (const { page, html } of all) {
      expect(page.title.length).toBeGreaterThan(5);
      expect(page.description.length).toBeGreaterThan(20);
      expect(html).not.toContain("<script");
    }
  });

  it("only pricing, earn, contact and privacy have anything live", () => {
    const live = all.filter(({ page }) => page.islands.length > 0).map(({ page }) => page.path);
    expect(live.sort()).toEqual(["/contact/", "/earn/", "/pricing/", "/privacy/"]);
  });

  it("never say we supply models, images or transcription, and name no made-up person or number", () => {
    const banned =
      /\b(transcri\w+|ready-made models?|pre-?trained models?|our models|included models|testimonial|trusted by|join \d|\d[\d,]*\+? (happy )?(users|customers))\b/i;
    for (const { page, html } of all) {
      expect(text(html), page.path).not.toMatch(banned);
    }
    expect(text(all.find(({ page }) => page.path === "/")!.html)).toContain(
      "You bring your own model, data and code",
    );
  });

  it("name no model we might seem to supply on the GPUs page, only sizes and your own models", () => {
    const gpus = text(all.find(({ page }) => page.path === "/gpus/")!.html);
    expect(gpus).not.toMatch(/stable diffusion|whisper|llama|mistral|\bvision\b|\bspeech\b/i);
    expect(gpus).toContain("your own models");
  });

  it("promise the host's protections as what hosting will have, after our safety tests", () => {
    const earn = text(all.find(({ page }) => page.path === "/earn/")!.html);
    expect(earn).toContain("Hosting opens after our safety tests");
    expect(earn).not.toMatch(/\bwork runs in a sandbox\b|Safe by design/);
    const landing = text(all.find(({ page }) => page.path === "/")!.html);
    expect(landing).toContain("What you can run");
    expect(landing).not.toContain("What you can run today");
  });

  it("say typical wherever the GPUs page says what a card manages", () => {
    const gpus = text(all.find(({ page }) => page.path === "/gpus/")!.html);
    expect(gpus.match(/typical/gi)?.length).toBeGreaterThanOrEqual(16);
  });

  it("leave terms and privacy as placeholders, and contact as a form with the grievance officer", () => {
    for (const path of ["/terms/", "/privacy/"]) {
      const html = all.find(({ page }) => page.path === path)!.html;
      expect(text(html)).toContain("Being drafted.");
    }
    const contact = all.find(({ page }) => page.path === "/contact/")!.html;
    expect(contact).toContain('name="topic"');
    expect(contact).toContain('name="website"');
    for (const path of ["/contact/", "/privacy/"]) {
      // The officer's details come from the control plane, never from the page.
      expect(all.find(({ page }) => page.path === path)!.html).toContain("data-grievance");
    }
  });

  it("offer the Windows app only once the API says there is one, in the same box as coming soon", () => {
    const earn = all.find(({ page }) => page.path === "/earn/")!.html;
    expect(text(earn)).toContain("Windows app: coming soon");
    expect(earn).toMatch(
      /<a data-role="download" hidden="" href="https:\/\/api\.example\.invalid\/v1\/agent-releases\/latest\/setup"/,
    );
  });

  it("have a Show me how page for each laptop maker the setup app knows, and one for the rest", () => {
    for (const maker of ["asus", "msi", "lenovo", "hp", "acer", "dell", "other"]) {
      const help = all.find(({ page }) => page.path === `/help/virtualization/${maker}/`);
      expect(help, maker).toBeDefined();
      expect(text(help!.html)).toContain("UEFI Firmware Settings");
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
