// Puts the console's contact sheet on one page (ADR 0057): every picture web/e2e/contact-sheet.spec.ts saved, by screen,
// each opening at full size. Usage: node scripts/contact-sheet-index.ts <folder>.
import { readdirSync, writeFileSync } from "node:fs";

const folder = process.argv[2] ?? "contact-sheet";
const pictures = readdirSync(folder)
  .filter((file) => file.endsWith(".png"))
  .sort();
if (pictures.length === 0) {
  console.error(`no pictures in ${folder}`);
  process.exit(1);
}

const screens = new Map<string, string[]>();
for (const file of pictures) {
  const screen = file.split("--")[0]!;
  screens.set(screen, [...(screens.get(screen) ?? []), file]);
}

const escape = (text: string) =>
  text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");

const figure = (file: string) => {
  const [, state = "", theme = "", width = ""] = file.replace(".png", "").split("--");
  const words = `${state.replaceAll("-", " ")}, ${theme}, ${width} px`;
  return `<figure><a href="${escape(file)}"><img loading="lazy" src="${escape(file)}" alt="${escape(words)}"></a><figcaption>${escape(words)}</figcaption></figure>`;
};

writeFileSync(
  `${folder}/index.html`,
  `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>The console's contact sheet</title>
<style>
  body { font: 14px/1.4 system-ui, sans-serif; margin: 24px; background: #fff; color: #111; }
  h2 { margin: 32px 0 12px; text-transform: capitalize; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; }
  figure { margin: 0; }
  img { width: 100%; max-height: 320px; object-fit: cover; object-position: top; border: 1px solid #ccc; }
  figcaption { margin-top: 4px; color: #555; }
</style>
</head>
<body>
<h1>The console's contact sheet</h1>
<p>${pictures.length} pictures of ${screens.size} screens, from fixtures. Each opens at full size.</p>
${[...screens.entries()]
  .map(
    ([screen, files]) =>
      `<section><h2>${escape(screen.replaceAll("-", " "))}</h2><div class="grid">${files.map(figure).join("")}</div></section>`,
  )
  .join("\n")}
</body>
</html>
`,
);
console.log(`contact sheet: ${pictures.length} pictures of ${screens.size} screens in ${folder}/index.html`);
