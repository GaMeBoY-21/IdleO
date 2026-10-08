// Lays out the review gallery (web/README.md "The review gallery"): every screen and state the screens tests
// captured into GALLERY_DIR, in light and dark, at 390 and 1280 px, side by side in one index.html, with the
// recordings at the top. With BEFORE_DIR (an earlier gallery), each fix from the last review is shown beside how it
// looked before. The folder is under the gitignored private/; nothing here is committed, and the data is invented.
import { copyFileSync, existsSync, readdirSync, writeFileSync } from "node:fs";

const dir = process.env.GALLERY_DIR;
if (!dir) {
  throw new Error("set GALLERY_DIR");
}
const before = process.env.BEFORE_DIR;
const files = readdirSync(dir);
const variants = ["phone-light", "phone-dark", "laptop-light", "laptop-dark"];
const screens = [
  ...new Set(
    files
      .filter((file) => file.endsWith(".png") && !file.startsWith("before-"))
      .map((file) => file.split("--")[0]!),
  ),
];
const order = ["sign-in", "welcome", "home", "book", "session-", "sessions", "credit", "not-found"];
screens.sort((a, b) => {
  const rank = (name: string) => order.findIndex((prefix) => name.startsWith(prefix));
  return rank(a) - rank(b) || a.localeCompare(b);
});
const title = (name: string) => name.replaceAll("-", " ").replace(/^./, (first) => first.toUpperCase());
const videos = files.filter((file) => file.endsWith(".webm")).sort();

/** The fixes from the review of the foundations, each with the pictures that show it. */
const fixes = [
  {
    what: "The invented data adds up: on hold covers what the running session has used, the total is available plus on hold, and a new account starts with a round ₹500.",
    screens: ["home", "welcome"],
  },
  {
    what: "On a phone the running session comes first, and credit appears once: the header's credit chip is hidden on Home. The visible “Home” title is gone (a hidden heading stays for screen readers).",
    screens: ["home"],
  },
  { what: "Template and session names wrap; nothing is cut off.", screens: ["home", "session-running"] },
  {
    what: "Times are relative (“started 22 min ago”), with the exact time on hover; elapsed time ticks.",
    screens: ["home", "session-running"],
  },
  {
    what: "One stale notice per screen, at the top, and nothing says “Live” while figures are stale.",
    screens: ["home-stale"],
  },
  {
    what: "Credit history: the whole row is the link to its session, with a chevron and no underline.",
    screens: ["credit"],
  },
  {
    what: "Home on a laptop: sessions in the wider left column, credit and “Book again” on the right. Welcome shows the starting credit and three steps, with “Book your first session”.",
    screens: ["home", "welcome"],
  },
];

const figure = (file: string, caption: string) =>
  files.includes(file) || existsSync(`${dir}/${file}`)
    ? `<figure class="${file.includes("phone") ? "phone" : "laptop"}"><a href="${file}"><img src="${file}" loading="lazy" alt="${caption}"></a><figcaption>${caption}</figcaption></figure>`
    : "";

let fixRows = "";
if (before) {
  fixRows = fixes
    .map((fix, index) => {
      const pairs = fix.screens
        .flatMap((screen) =>
          ["phone-light", "laptop-light"].map((variant) => {
            const name = `${screen}--${variant}.png`;
            if (existsSync(`${before}/${name}`)) {
              copyFileSync(`${before}/${name}`, `${dir}/before-${name}`);
            }
            return `<div class="pair">${figure(`before-${name}`, `Before: ${title(screen)}, ${variant.replace("-", ", ")}`)}${figure(name, `After: ${title(screen)}, ${variant.replace("-", ", ")}`)}</div>`;
          }),
        )
        .join("\n");
      return `<section id="fix-${index + 1}"><h3>${index + 1}. ${fix.what}</h3><div class="row">${pairs}</div></section>`;
    })
    .join("\n");
}

const rows = screens
  .map(
    (screen) => `
  <section id="${screen}">
    <h2>${title(screen)}</h2>
    <div class="row">
      ${variants.map((variant) => figure(`${screen}--${variant}.png`, `${title(screen)}, ${variant.replace("-", ", ")}`)).join("\n      ")}
    </div>
  </section>`,
  )
  .join("\n");

writeFileSync(
  `${dir}/index.html`,
  `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Booking and live session: review gallery</title>
<style>
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; background: #f2f2f5; color: #16161d; }
  header { padding: 24px 32px; background: #fff; border-bottom: 1px solid #e2e2e8; position: sticky; top: 0; z-index: 1; }
  header h1 { margin: 0 0 4px; font-size: 20px; } header p { margin: 0; color: #55556a; }
  nav { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px; } nav a { font-size: 13px; color: #4136c4; }
  main { padding: 24px 32px; } section { margin-bottom: 40px; } h2 { font-size: 17px; margin: 0 0 12px; } h3 { font-size: 15px; font-weight: 500; margin: 0 0 12px; max-width: 80ch; }
  .row { display: flex; gap: 16px; align-items: flex-start; overflow-x: auto; padding-bottom: 8px; }
  .pair { display: flex; gap: 8px; padding: 8px; background: #fff; border: 1px solid #e2e2e8; border-radius: 8px; flex: none; }
  figure { margin: 0; flex: none; } figure.phone img { width: 230px; } figure.laptop img { width: 520px; }
  img { display: block; border: 1px solid #d0d0d8; border-radius: 8px; background: #fff; }
  figcaption { font-size: 12px; color: #55556a; margin-top: 4px; } video { border: 1px solid #d0d0d8; border-radius: 8px; max-width: 100%; }
  .videos { display: flex; gap: 16px; flex-wrap: wrap; align-items: flex-start; } .videos video.phone { width: 300px; } .videos video.laptop { width: 640px; }
</style></head><body>
<header><h1>Booking and live session: review gallery</h1>
<p>${screens.length} screens and states, each in light and dark at 390 px and 1280 px, ${videos.length} recordings${before ? ", and the fixes from the first review beside how they looked before" : ""}. Invented data throughout. Click a picture for full size.</p>
<nav>${before ? '<a href="#fixes">Fixes</a>' : ""}<a href="#recordings">Recordings</a>${screens.map((screen) => `<a href="#${screen}">${title(screen)}</a>`).join("")}</nav></header>
<main>
${before ? `<section id="fixes"><h2>Fixes from the first review</h2>${fixRows}</section>` : ""}
<section id="recordings"><h2>Recordings</h2><div class="videos">${videos
    .map(
      (video) =>
        `<figure><video class="${video.includes("--phone") ? "phone" : "laptop"}" src="${video}" controls muted playsinline></video><figcaption>${title(video.replace(".webm", "").replace("--", ", on a "))}</figcaption></figure>`,
    )
    .join("")}</div></section>
${rows}
</main></body></html>
`,
);
console.log(
  `gallery: ${screens.length} screens and states, ${videos.length} recordings in ${dir}/index.html`,
);
