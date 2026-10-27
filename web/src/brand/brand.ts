/**
 * The brand, in one place (web/DESIGN.md "Brand"): the product name isn't decided, so the working name, the
 * accent colour, the wordmark (Wordmark.tsx) and the favicon (icon.svg) all live here, and the final brand is
 * one small change. The design tokens take the ring's colour from here.
 */
export const brand = {
  name: "IdleO",
  description: "Rent a GPU by the minute, in rupees.",
} as const;

/**
 * The brand's accent, the violet of the labels and the focus ring (web/DESIGN.md "Colour"): ion at night, ion-deep by
 * day. The focus ring is volt at night, which shows on every surface. Contrast is tested in tokens.test.ts.
 */
export const ring = {
  dark: { brand: "#8E80FF", focus: "#C8FF3D" },
  light: { brand: "#5B4BE8", focus: "#5B4BE8" },
} as const;
