// Saves what isn't a web page as a picture, for the product owner to review, into private/screens/<date>/<group>/
// (git-ignored). The web's screens, every site in each state at five widths, are the UI review's (web/e2e/ui-review.ts).
//
//   pnpm screenshots emails pdfs tray  # every email (light and dark), a sample of each PDF, the tray's icons
//   pnpm screenshots windows-setup     # the Windows setup window, every screen at 720 x 520, in Windows' light and dark
//   pnpm screenshots sheet             # private/screens/<date>/index.html: all of the above on one page
//   SHOTS_DATE=2026-10-27 pnpm screenshots emails
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { chromium, type Browser } from "@playwright/test";

const repo = fileURLToPath(new URL("../../", import.meta.url));
const date = process.env.SHOTS_DATE ?? new Date().toISOString().slice(0, 10);
const out = (group: string) => {
  const directory = `${repo}private/screens/${date}/${group}`;
  mkdirSync(directory, { recursive: true });
  return directory;
};

const THEMES = ["dark", "light"] as const;

/**
 * The Windows setup window (agent/setup): its page, assembled the way ui.rs does it (the style sheet with the fonts in it,
 * the script) with agent/setup/dev/preview.js in front of the script, so a fake IPC stands in for Rust and every screen can
 * be shown. Saved at the window's real size, in both of Windows' themes. The assembled page is kept beside the pictures,
 * as preview.html: open it to click through the screens.
 */
async function windowsSetup(browser: Browser): Promise<number> {
  const setup = `${repo}agent/setup/`;
  const read = (file: string) => readFileSync(`${setup}${file}`, "utf8");
  const font = (file: string) => readFileSync(`${setup}assets/fonts/${file}`).toString("base64");
  const style = read("assets/style.css")
    .replace("/*FONT_INTER*/", font("inter-400-700.woff2"))
    .replace("/*FONT_DISPLAY*/", font("archivo-125-900.woff2"))
    .replace("/*FONT_DISPLAY_LOWER*/", font("archivo-125-900-lower.woff2"))
    .replace("/*FONT_TITLE*/", font("archivo-112-800.woff2"));
  const html = read("assets/index.html")
    .replace("/*STYLE*/", () => style)
    .replace("/*SCRIPT*/", () => `${read("dev/preview.js")}\n${read("assets/app.js")}`);
  const directory = out("windows-setup");
  writeFileSync(`${directory}/preview.html`, html);
  let saved = 0;
  for (const theme of THEMES) {
    const context = await browser.newContext({
      viewport: { width: 720, height: 520 },
      deviceScaleFactor: 2,
      colorScheme: theme,
      locale: "en-IN",
      reducedMotion: "reduce",
    });
    const page = await context.newPage();
    await page.goto(`file://${directory}/preview.html`);
    const names = await page.evaluate(
      () => (window as unknown as { idleoPreview: { names: string[] } }).idleoPreview.names,
    );
    for (const name of names) {
      await page.evaluate(
        (screen) =>
          (window as unknown as { idleoPreview: { show(n: string): boolean } }).idleoPreview.show(screen),
        name,
      );
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: `${directory}/${name}--720-${theme}.png` });
      saved += 1;
    }
    await context.close();
  }
  return saved;
}

/** Runs a command from the repository's root, in the pinned toolchains' environment (scripts/lib/toolchains.sh). */
function run(command: string): void {
  const result = spawnSync(
    "bash",
    ["-c", `root="${repo}"; source "$root/scripts/lib/toolchains.sh"; cd "$root"; ${command}`],
    { stdio: "inherit" },
  );
  if (result.status !== 0) {
    throw new Error(`failed: ${command}`);
  }
}

/** Every email, as the control plane's EmailSamples test writes it, and each seen in a light and a dark email app. */
async function emails(browser: Browser): Promise<number> {
  const directory = out("emails");
  run(`cd control-plane && ./mvnw -q test -Dtest=EmailSamples -Didleo.emails.out="${directory}"`);
  let saved = 0;
  for (const theme of THEMES) {
    const context = await browser.newContext({
      viewport: { width: 640, height: 800 },
      deviceScaleFactor: 1,
      colorScheme: theme,
    });
    const page = await context.newPage();
    for (const file of readdirSync(directory).filter((name) => name.endsWith(".html"))) {
      await page.goto(`file://${directory}/${file}`);
      await page.screenshot({
        path: `${directory}/${file.replace(".html", "")}--640-${theme}.png`,
        fullPage: true,
      });
      saved += 1;
    }
    await context.close();
  }
  return saved;
}

/** A sample of each invoice, receipt, credit note and the payout statement, as PDFs with a picture of the first page. */
function pdfs(): number {
  const directory = out("pdfs");
  run(
    `cd control-plane && ./mvnw -q test -Dtest='InvoicePdfSamples,StatementPdfSamples' -Didleo.pdfs.out="${directory}"`,
  );
  return readdirSync(directory).filter((file) => file.endsWith(".pdf")).length;
}

/** The tray's four icons, on a clear ground and on Windows' dark and light taskbars. */
function tray(): number {
  const directory = out("tray");
  run(`cd agent && cargo run -q -p tray --example save_icons -- "${directory}"`);
  return readdirSync(directory).filter((file) => file.endsWith(".png")).length;
}

/**
 * The contact sheet, private/screens/<date>/index.html: every picture of the other groups with its route or name, grouped
 * (website, app, admin, Windows setup, tray, emails, PDFs), the two themes side by side, so the whole product can be
 * reviewed in one page. Paths are relative, so the folder can be moved.
 */
function sheet(): number {
  const root = `${repo}private/screens/${date}`;
  const escape = (text: string) => text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
  const titles: Record<string, string> = {
    "windows-setup": "Windows setup",
    tray: "Tray",
    emails: "Emails",
    pdfs: "PDFs",
  };
  let count = 0;
  const sections = Object.entries(titles).flatMap(([group, title]) => {
    let files: string[];
    try {
      files = readdirSync(`${root}/${group}`).filter((file) => file.endsWith(".png"));
    } catch {
      return [];
    }
    // A picture is `<name>--<size>-<theme>.png`; the same name and size in two themes is one figure.
    const figures = new Map<string, string[]>();
    for (const file of files.sort()) {
      const key = file.replace(/-(light|dark)\.png$/, "");
      figures.set(key, [...(figures.get(key) ?? []), file]);
    }
    const cells = [...figures].map(([key, versions]) => {
      count += versions.length;
      const [name = key, size = ""] = key.split("--");
      const images = versions
        .map(
          (file) =>
            `<a href="${group}/${file}"><img loading="lazy" src="${group}/${file}" alt="${escape(name)}, ${file.includes("-light.") ? "light" : file.includes("-dark.") ? "dark" : ""}"></a>`,
        )
        .join("");
      return `<figure><figcaption><b>${escape(name)}</b> <span>${escape(size)}</span></figcaption><div class="pair">${images}</div></figure>`;
    });
    return [
      `<section id="${group}"><h2>${title} <small>${cells.length}</small></h2><div class="grid">${cells.join("")}</div></section>`,
    ];
  });
  const nav = Object.entries(titles)
    .map(([group, title]) => `<a href="#${group}">${title}</a>`)
    .join(" ");
  writeFileSync(
    `${root}/index.html`,
    `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>IdleO screens, ${date}</title>
<style>
body { margin: 0; font: 14px/1.4 system-ui, sans-serif; background: #f4f3fa; color: #0c0a1f; }
header { position: sticky; top: 0; background: #0c0a1f; color: #fff; padding: 12px 24px; display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
header a { color: #c8ff3d; }
h1 { font-size: 16px; margin: 0 16px 0 0; }
section { padding: 24px; }
h2 { margin: 0 0 16px; } h2 small { font-weight: 400; color: #5e5a80; }
.grid { display: flex; flex-wrap: wrap; gap: 24px; align-items: flex-start; }
figure { margin: 0; }
figcaption span { color: #5e5a80; }
.pair { display: flex; gap: 8px; margin-top: 6px; }
img { display: block; max-width: 420px; max-height: 520px; border: 1px solid #c9c5e0; border-radius: 8px; background: #fff; }
</style>
<header><h1>IdleO screens, ${date}</h1>${nav}</header>
${sections.join("\n")}
</html>`,
  );
  return count;
}

const groups = process.argv.slice(2);
if (groups.length === 0) {
  console.error(
    "screenshots: name the groups: windows-setup, emails, pdfs, tray, sheet (the web's screens: pnpm ui-review)",
  );
  process.exit(2);
}
const wanted = groups;
for (const group of wanted) {
  if (group === "windows-setup" || group === "emails") {
    const browser = await chromium.launch();
    try {
      const saved = group === "emails" ? await emails(browser) : await windowsSetup(browser);
      console.log(`screenshots: ${saved} pictures of the ${group} in private/screens/${date}/${group}/`);
    } finally {
      await browser.close();
    }
  } else if (group === "pdfs") {
    console.log(`screenshots: ${pdfs()} documents in private/screens/${date}/pdfs/`);
  } else if (group === "tray") {
    console.log(`screenshots: ${tray()} pictures of the tray icons in private/screens/${date}/tray/`);
  } else if (group === "sheet") {
    console.log(`screenshots: the contact sheet has ${sheet()} pictures: private/screens/${date}/index.html`);
  } else {
    throw new Error(
      `unknown group ${group}: windows-setup, emails, pdfs, tray or sheet (the web: pnpm ui-review)`,
    );
  }
}
