// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { contrast } from "./contrast";
import { existsSync, statSync } from "node:fs";
import { contrastPairs, displayScale, fonts, preloadedFonts, themes, typeScale } from "./tokens";

/** DESIGN.md's contrast table, by "fg on bg": [dark, light, minimum]. */
function documentedRatios(): Map<string, [number, number, number]> {
  const design = readFileSync(new URL("../../DESIGN.md", import.meta.url), "utf8");
  const rows = new Map<string, [number, number, number]>();
  for (const line of design.split("\n")) {
    const match = /^\| `([a-z0-9-]+)` on `([a-z0-9-]+)` \| ([\d.]+) \| ([\d.]+) \| ([\d.]+) \|$/.exec(line);
    if (match) {
      rows.set(`${match[1]} on ${match[2]}`, [Number(match[3]), Number(match[4]), Number(match[5])]);
    }
  }
  return rows;
}

describe("the design tokens", () => {
  it("meet WCAG 2.2 AA for every listed pair, in both themes", () => {
    for (const theme of ["light", "dark"] as const) {
      for (const { fg, bg, min } of contrastPairs) {
        const ratio = contrast(themes[theme][fg], themes[theme][bg]);
        expect(ratio, `${theme}: ${fg} on ${bg}`).toBeGreaterThanOrEqual(min);
      }
    }
  });

  it("match DESIGN.md's contrast table exactly, pair for pair", () => {
    const documented = documentedRatios();
    expect([...documented.keys()].sort()).toEqual(contrastPairs.map(({ fg, bg }) => `${fg} on ${bg}`).sort());
    for (const { fg, bg, min } of contrastPairs) {
      const [dark, light, documentedMin] = documented.get(`${fg} on ${bg}`)!;
      expect(contrast(themes.light[fg], themes.light[bg])).toBeCloseTo(light, 2);
      expect(contrast(themes.dark[fg], themes.dark[bg])).toBeCloseTo(dark, 2);
      expect(documentedMin).toBe(min);
    }
  });

  it("define the same colour tokens in both themes", () => {
    expect(Object.keys(themes.dark).sort()).toEqual(Object.keys(themes.light).sort());
  });

  it("keep the type scale to the thirteen sizes DESIGN.md lists, and Archivo's to the eleven", () => {
    const design = readFileSync(new URL("../../DESIGN.md", import.meta.url), "utf8");
    for (const [name, [size, lineHeight]] of Object.entries(typeScale)) {
      expect(design, name).toContain(`| \`text-${name}\` | ${size} / ${lineHeight} px |`);
    }
    expect(Object.keys(typeScale)).toHaveLength(13);
    for (const name of Object.keys(displayScale)) {
      expect(design, name).toContain(`| \`text-${name}\` |`);
    }
    expect(Object.keys(displayScale)).toHaveLength(11);
  });

  it("check every colour role in both themes: nothing is listed that a theme lacks, and each text colour is paired", () => {
    const paired = new Set(contrastPairs.flatMap(({ fg, bg }) => [fg, bg]));
    for (const name of [
      "text",
      "text-2",
      "text-3",
      "primary",
      "primary-fg",
      "link",
      "running",
      "danger",
      "ok-fg",
      "warn-fg",
      "ink",
      "on-night",
      "on-night-2",
      "on-night-3",
    ] as const) {
      expect(paired.has(name), name).toBe(true);
    }
  });

  it("keep volt off light backgrounds as text: in Day Shift the roles that carry it are not volt", () => {
    for (const role of ["link", "running", "progress", "brand"] as const) {
      expect(themes.light[role].toLowerCase(), role).not.toBe(themes.light.volt.toLowerCase());
    }
    expect(themes.light["primary-fg"]).toBe(themes.light.volt);
    expect(themes.light.primary).toBe(themes.light.ink);
  });
});

describe("the fonts", () => {
  const publicFonts = new URL("../../public/fonts/", import.meta.url);

  it("are the self-hosted files, each with its licence, and Plus Jakarta Sans is gone from the web app", () => {
    for (const font of Object.values(fonts)) {
      expect(existsSync(new URL(font.file, publicFonts)), font.file).toBe(true);
      if (font.more) {
        expect(existsSync(new URL(font.more.file, publicFonts)), font.more.file).toBe(true);
      }
    }
    for (const licence of ["Archivo", "Inter", "JetBrainsMono"]) {
      expect(existsSync(new URL(`LICENSE-${licence}.txt`, publicFonts)), licence).toBe(true);
    }
    expect(existsSync(new URL("plus-jakarta-sans-subset.woff2", publicFonts))).toBe(false);
  });

  it("stay small: what a first screen preloads is under 30 KB in all, every file under 20 KB, the whole set under 80 KB", () => {
    const size = (file: string) => statSync(new URL(file, publicFonts)).size;
    const files = Object.values(fonts).flatMap((font) => [font.file, ...(font.more ? [font.more.file] : [])]);
    for (const file of files) {
      expect(size(file), file).toBeLessThan(20 * 1024);
    }
    expect(preloadedFonts.reduce((sum, name) => sum + size(fonts[name].file), 0)).toBeLessThan(30 * 1024);
    expect(files.reduce((sum, file) => sum + size(file), 0)).toBeLessThan(80 * 1024);
  });

  it("keep the two ranges apart: the first-screen file and the accented letters share no character", () => {
    const expand = (range: string) =>
      range.split(",").flatMap((part) => {
        const [from, to = from] = part
          .replace(/U\+/g, "")
          .split("-")
          .map((hex) => parseInt(hex, 16));
        return Array.from({ length: to! - from! + 1 }, (_, index) => from! + index);
      });
    const base = new Set(expand(fonts.sans.range));
    expect(expand(fonts.sans.more.range).filter((code) => base.has(code))).toEqual([]);
  });
});
