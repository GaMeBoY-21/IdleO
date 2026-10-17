// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { contrast } from "./contrast";
import { contrastPairs, themes, typeScale } from "./tokens";

/** DESIGN.md's contrast table, by "fg on bg": [light, dark, minimum]. */
function documentedRatios(): Map<string, [number, number, number]> {
  const design = readFileSync(new URL("../../DESIGN.md", import.meta.url), "utf8");
  const rows = new Map<string, [number, number, number]>();
  for (const line of design.split("\n")) {
    const match = /^\| `([a-z-]+)` on `([a-z-]+)` \| ([\d.]+) \| ([\d.]+) \| ([\d.]+) \|$/.exec(line);
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
      const [light, dark, documentedMin] = documented.get(`${fg} on ${bg}`)!;
      expect(contrast(themes.light[fg], themes.light[bg])).toBeCloseTo(light, 2);
      expect(contrast(themes.dark[fg], themes.dark[bg])).toBeCloseTo(dark, 2);
      expect(documentedMin).toBe(min);
    }
  });

  it("define the same colour tokens in both themes", () => {
    expect(Object.keys(themes.dark).sort()).toEqual(Object.keys(themes.light).sort());
  });

  it("keep the type scale to the twelve sizes DESIGN.md lists", () => {
    const design = readFileSync(new URL("../../DESIGN.md", import.meta.url), "utf8");
    for (const [name, [size, lineHeight]] of Object.entries(typeScale)) {
      expect(design, name).toContain(`| \`text-${name}\` | ${size} / ${lineHeight} px |`);
    }
    expect(Object.keys(typeScale)).toHaveLength(12);
  });
});
