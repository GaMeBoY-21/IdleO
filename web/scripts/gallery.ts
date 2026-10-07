// Lays out the review gallery (web/README.md "The review gallery"): every screen and state the screens tests
// captured into GALLERY_DIR, in light and dark, at 390 and 1280 px, side by side in one index.html, with the
// sign-in recording at the top. The folder is under the gitignored private/; nothing here is committed.
import { readdirSync, writeFileSync } from "node:fs";

const dir = process.env.GALLERY_DIR;
if (!dir) {
  throw new Error("set GALLERY_DIR");
}
const files = readdirSync(dir);
const variants = ["phone-light", "phone-dark", "laptop-light", "laptop-dark"];
const screens = [
  ...new Set(files.filter((file) => file.endsWith(".png")).map((file) => file.split("--")[0]!)),
];
const order = ["sign-in", "welcome", "home", "session", "credit", "book", "not-found"];
screens.sort((a, b) => {
  const rank = (name: string) => order.findIndex((prefix) => name.startsWith(prefix));
  return rank(a) - rank(b) || a.localeCompare(b);
});
const title = (name: string) => name.replaceAll("-", " ").replace(/^./, (first) => first.toUpperCase());
const video = files.find((file) => file.endsWith(".webm"));

const rows = screens
  .map(
    (screen) => `
  <section id="${screen}">
    <h2>${title(screen)}</h2>
    <div class="row">
      ${variants
        .map((variant) => {
          const file = `${screen}--${variant}.png`;
          return files.includes(file)
            ? `<figure class="${variant.startsWith("phone") ? "phone" : "laptop"}"><a href="${file}"><img src="${file}" loading="lazy" alt="${title(screen)}, ${variant.replace("-", ", ")}"></a><figcaption>${variant.replace("-", " · ")}</figcaption></figure>`
            : "";
        })
        .join("\n      ")}
    </div>
  </section>`,
  )
  .join("\n");

writeFileSync(
  `${dir}/index.html`,
  `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Web foundations: review gallery</title>
<style>
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; background: #f2f2f5; color: #16161d; }
  header { padding: 24px 32px; background: #fff; border-bottom: 1px solid #e2e2e8; position: sticky; top: 0; z-index: 1; }
  header h1 { margin: 0 0 4px; font-size: 20px; } header p { margin: 0; color: #55556a; }
  nav { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px; } nav a { font-size: 13px; color: #4136c4; }
  main { padding: 24px 32px; } section { margin-bottom: 40px; } h2 { font-size: 17px; margin: 0 0 12px; }
  .row { display: flex; gap: 16px; align-items: flex-start; overflow-x: auto; padding-bottom: 8px; }
  figure { margin: 0; flex: none; } figure.phone img { width: 230px; } figure.laptop img { width: 520px; }
  img { display: block; border: 1px solid #d0d0d8; border-radius: 8px; background: #fff; }
  figcaption { font-size: 12px; color: #55556a; margin-top: 4px; } video { border: 1px solid #d0d0d8; border-radius: 8px; width: 300px; }
</style></head><body>
<header><h1>Web foundations: review gallery</h1>
<p>${screens.length} screens and states, each in light and dark at 390 px and 1280 px. Invented data throughout. Click a picture for full size.</p>
<nav>${screens.map((screen) => `<a href="#${screen}">${title(screen)}</a>`).join("")}</nav></header>
<main>
${video ? `<section><h2>Sign-in to home, on a phone</h2><video src="${video}" controls muted playsinline></video></section>` : ""}
${rows}
</main></body></html>
`,
);
console.log(`gallery: ${screens.length} screens and states in ${dir}/index.html`);
