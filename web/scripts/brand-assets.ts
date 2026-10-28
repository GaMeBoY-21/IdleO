// Draws the brand's pictures from the one icon (public/icon.svg) and the fonts, into public/: the icons the web app and the
// website use (apple-touch-icon.png, icon-192.png, icon-512.png, favicon.ico) and the website's share picture
// (og-image.png, 1200 x 630, in the Night Shift look, with the line from brand.share). Run it only to change the brand:
// the output is committed.
//
//   node scripts/brand-assets.ts
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";
import { brand } from "../src/brand/brand.ts";

const here = (path: string) => fileURLToPath(new URL(`../${path}`, import.meta.url));
const font = (file: string) => readFileSync(here(`public/fonts/${file}`)).toString("base64");
const icon = readFileSync(here("public/icon.svg"), "utf8");

const faces = `
@font-face { font-family: "Archivo Display"; font-weight: 900; src: url(data:font/woff2;base64,${font("archivo-125-900.woff2")}) format("woff2"); unicode-range: U+0020-0060, U+007B-007E, U+00A0, U+00A9, U+00B7, U+00D7, U+2013-2014, U+2018-201D, U+2022, U+2026, U+20B9, U+2190-2193, U+2212, U+2248; }
@font-face { font-family: "Archivo Display"; font-weight: 900; src: url(data:font/woff2;base64,${font("archivo-125-900-lower.woff2")}) format("woff2"); unicode-range: U+0061-007A; }`;

/** The share picture: the wordmark, the line in Archivo uppercase with its second sentence in volt, and the ring. */
const [first = "", second = ""] = brand.share
  .split(". ")
  .map((part, index, all) => (index < all.length - 1 ? `${part}.` : part));
const card = `<!doctype html><meta charset="utf-8"><style>${faces}
* { box-sizing: border-box; }
body { margin: 0; width: 1200px; height: 630px; background: #0C0A1F; color: #fff; font-family: "Archivo Display", Arial, sans-serif; font-weight: 900; position: relative; overflow: hidden; }
.glow { position: absolute; right: -180px; top: -220px; width: 760px; height: 760px; border-radius: 50%; background: #5B4BE8; opacity: 0.55; }
.ring { position: absolute; right: 70px; bottom: 60px; width: 300px; height: 300px; }
.mark { position: absolute; left: 72px; top: 64px; font-size: 54px; letter-spacing: -0.03em; line-height: 1; }
h1 { position: absolute; left: 72px; bottom: 72px; margin: 0; width: 760px; font-size: 92px; line-height: 0.98; letter-spacing: -0.03em; text-transform: uppercase; }
h1 span { color: #C8FF3D; display: block; }
</style><div class="glow"></div><div class="ring">${icon.replace("<svg ", '<svg width="300" height="300" ')}</div>
<div class="mark">${brand.name.toLowerCase()}</div>
<h1>${first}<span>${second}</span></h1>`;

const png = async (
  page: import("@playwright/test").Page,
  html: string,
  width: number,
  height: number,
  path: string,
) => {
  await page.setViewportSize({ width, height });
  await page.setContent(html);
  await page.evaluate(() => document.fonts.ready);
  writeFileSync(
    here(path),
    await page.screenshot({ clip: { x: 0, y: 0, width, height }, omitBackground: true }),
  );
};

/** An icon on a page of its own size. */
const square = (size: number) =>
  `<!doctype html><style>body { margin: 0; } svg { display: block; }</style>${icon.replace("<svg ", `<svg width="${size}" height="${size}" `)}`;

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await png(page, card, 1200, 630, "public/og-image.png");
  await png(page, square(180), 180, 180, "public/apple-touch-icon.png");
  await png(page, square(192), 192, 192, "public/icon-192.png");
  await png(page, square(512), 512, 512, "public/icon-512.png");
  // favicon.ico: three PNG images (16, 32, 48 pixels), which every current browser reads.
  const images: Buffer[] = [];
  for (const size of [16, 32, 48]) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(square(size));
    images.push(
      await page.screenshot({ clip: { x: 0, y: 0, width: size, height: size }, omitBackground: true }),
    );
  }
  const header = Buffer.alloc(6 + 16 * images.length);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(images.length, 4);
  let offset = header.length;
  images.forEach((image, index) => {
    const size = [16, 32, 48][index]!;
    const entry = 6 + 16 * index;
    header.writeUInt8(size, entry);
    header.writeUInt8(size, entry + 1);
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(image.length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += image.length;
  });
  writeFileSync(here("public/favicon.ico"), Buffer.concat([header, ...images]));
} finally {
  await browser.close();
}
