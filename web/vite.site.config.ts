import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

/**
 * The public pages' own build (ADR 0029), apart from the app's (vite.config.ts) so that nothing of the app can be in
 * it: its modules are only what src/site/ imports. The client build makes the style sheet and the two small scripts;
 * `vite build --config vite.site.config.ts --ssr src/site/render.tsx` makes the module that draws the pages, which
 * scripts/build-site.ts runs. Files go under /site-assets/, so they never meet the app's /assets/.
 */
export default defineConfig({
  plugins: [tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  // The fonts are the app's own, copied beside every build by the app's build; nothing else is public.
  publicDir: false,
  build: {
    outDir: process.env.IDLEO_SITE_DIST ?? "dist-site",
    target: "es2022",
    manifest: true,
    assetsDir: "site-assets",
    cssCodeSplit: true,
    rollupOptions: {
      input: {
        site: "src/site/site.css",
        pricing: "src/site/islands/pricing.ts",
        earn: "src/site/islands/earn.ts",
      },
    },
  },
});
