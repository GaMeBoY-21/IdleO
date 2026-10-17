/**
 * The brand, in one place (web/DESIGN.md "Brand"): the product name isn't decided, so the working name, the
 * ring's colour, the wordmark (Wordmark.tsx) and the favicon (icon.svg, the ring) all live here, and the final brand is
 * one small change. The design tokens take the ring's colour from here.
 */
export const brand = {
  name: "IdleO",
  description: "Rent a GPU by the minute, in rupees.",
} as const;

/**
 * The brand's one colour, the indigo of the wordmark's ring (web/DESIGN.md "Colour"): everything else is black and
 * white. It also draws the focus ring. Contrast is tested in tokens.test.ts.
 */
export const ring = {
  light: { brand: "#4B3FD1", focus: "#4B3FD1" },
  dark: { brand: "#8F86FF", focus: "#A39CFF" },
} as const;
