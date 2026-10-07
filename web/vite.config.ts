import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv, type Plugin } from "vite";
import { assertBuildSettings, type BuildEnv } from "./src/build-settings.ts";
import { themeBootScript } from "./src/design/theme.ts";

/** Writes the theme script into index.html's head, so it runs before the first paint (web/DESIGN.md "Colour"). */
function themeBoot(): Plugin {
  return {
    name: "idleo-theme-boot",
    transformIndexHtml: (html) => html.replace("%IDLEO_THEME_BOOT%", themeBootScript),
  };
}

export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, process.cwd(), ["VITE_", "IDLEO_"]), ...process.env } as BuildEnv;
  // A wrong combination (above all, the mock sign-in adapter outside a local build) stops here, before any
  // bundle exists (ADR 0018).
  assertBuildSettings(env);
  return {
    plugins: [react(), tailwindcss(), themeBoot()],
    resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
    // The adapter not chosen is dead code, so it's left out of the bundle entirely.
    define: { "import.meta.env.VITE_AUTH": JSON.stringify(env.VITE_AUTH) },
    build: {
      // IDLEO_DIST: a second build side by side, as the end-to-end test makes for the Firebase screens.
      outDir: process.env.IDLEO_DIST ?? "dist",
      target: "es2022",
      manifest: true,
      sourcemap: process.env.IDLEO_SOURCEMAP === "1",
    },
    server: { port: 3000, strictPort: true },
    preview: { port: 3000, strictPort: true },
  };
});
