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

/**
 * The frame's own code in one chunk (ADR 0018, web/README.md "The budgets"). The shell's small modules are shared with
 * the lazily loaded screens, and the bundler would otherwise give each its own file: every one is another request the
 * first screen waits on. Modules only later screens use stay out of it.
 */
const LATER =
  /src\/(lib\/(bytes|endings|log-signals)|api\/(logs|stream|session-follower|useLiveSession)|components\/ui\/Inputs|components\/shell\/(UserMenu|AccountRail|AppearanceChoice))\./;
function shellChunk(id: string): string | undefined {
  if (LATER.test(id)) {
    return undefined;
  }
  if (
    /\/src\/(components|lib|api|auth\/(index|store|types)|config|design|brand)\b/.test(id) ||
    /lucide-react\/dist\/esm\/(createLucideIcon|defaultAttributes|shared|Icon|icons\/(check|plus|cpu|chevron-right)\.)/.test(
      id,
    ) ||
    // The route a session's progress is drawn on, shared by Book's right panel and the session screen: another file,
    // and with it another round trip on a slow connection, if left apart (ADR 0031).
    /\/src\/features\/flow\/Route\./.test(id) ||
    // The words for every problem and the mutation code: shared by Book and the session screen, which would
    // otherwise each wait on another small file (ADR 0021).
    /query-core\/build\/modern\/mutationObserver|react-query\/build\/modern\/useMutation/.test(id) ||
    /node_modules\/(\.pnpm\/)?clsx/.test(id)
  ) {
    return "shell";
  }
  return undefined;
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
    define: {
      "import.meta.env.VITE_AUTH": JSON.stringify(env.VITE_AUTH),
      // Where the app's links to the website (Terms, Privacy, "← idleo") lead.
      "import.meta.env.IDLEO_SITE_URL": JSON.stringify(env.IDLEO_SITE_URL ?? ""),
    },
    build: {
      // IDLEO_DIST: a second build side by side, as the end-to-end test makes for the Firebase screens.
      outDir: process.env.IDLEO_DIST ?? "dist",
      target: "es2022",
      manifest: true,
      sourcemap: process.env.IDLEO_SOURCEMAP === "1",
      rollupOptions: { output: { manualChunks: shellChunk } },
    },
    server: { port: 3000, strictPort: true },
    preview: { port: 3000, strictPort: true },
  };
});
