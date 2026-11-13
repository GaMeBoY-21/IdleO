/**
 * The brand, in one place (web/DESIGN.md "Brand"): the product name isn't decided, so the working name, the
 * accent colour, the wordmark (Wordmark.tsx) and the favicon (icon.svg) all live here, and the final brand is
 * one small change.
 */
export const brand = {
  name: "IdleO",
  description: "Rent a GPU by the minute, in rupees.",
  /** The line on the website's share picture (public/og-image.png, made by scripts/brand-assets.ts). */
  share: "Rent a gaming GPU. Pay by the minute.",
} as const;
