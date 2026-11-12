import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv, type Plugin } from "vite";
import { assertBuildSettings, type BuildEnv } from "./src/build-settings.ts";
import { distOf, SIGN_IN_DEPS, SITE_FACTS, siteOf } from "./src/sites/sites.ts";
import { themeBootScript } from "./src/design/theme.ts";

/** Writes the theme script into index.html's head, so it runs before the first paint (web/DESIGN.md "Colour"). */
function themeBoot(): Plugin {
  return {
    name: "idleo-theme-boot",
    transformIndexHtml: (html) => html.replace("%IDLEO_THEME_BOOT%", themeBootScript),
  };
}

/**
 * The component gallery at /_gallery (web/README.md "The component gallery"): a development server's alone (`apply:
 * "serve"`), so no build has it; gallery.html is no build's input, and scripts/site-modules.ts fails a build that holds
 * any of src/gallery/.
 */
function galleryPage(): Plugin {
  return {
    name: "idleo-gallery",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if (!/^\/_gallery\/?(\?.*)?$/.test(request.url ?? "")) {
          next();
          return;
        }
        const page = readFileSync(fileURLToPath(new URL("./gallery.html", import.meta.url)), "utf8");
        server
          .transformIndexHtml(request.url!, page)
          .then((html) => {
            response.setHeader("Content-Type", "text/html; charset=utf-8");
            response.end(html);
          })
          .catch(next);
      });
    },
  };
}

/**
 * The main script, React and the frame's own code in one chunk (ADR 0018, web/README.md "The budgets"). The shell's small modules are
 * shared with the lazily loaded screens, and the bundler would otherwise give each its own file: every one is another request the
 * first screen waits on. Modules only later screens use stay out of it. Each screen's first load is this and one file of its own.
 */
const LATER =
  /src\/(lib\/(bytes|endings|log-signals|support|uploads|invite|payouts|problem-words)|api\/(logs|stream|session-follower|useLiveSession|wallet|images|privacy|support|payouts)|components\/shell\/(UserMenu|AccountRail|AppearanceChoice)|components\/ui\/(Sheet|Toast)|design\/theme)\./;
function shellChunk(id: string): string | undefined {
  if (/\/src\/(main|App)\.tsx|node_modules\/(\.pnpm\/)?(react|react-dom|scheduler)[@/]/.test(id)) {
    return "shell";
  }
  if (LATER.test(id)) {
    return undefined;
  }
  if (
    /\/src\/(components|lib|api|auth\/(index|store|types)|config|design|brand)\b/.test(id) ||
    /lucide-react\/dist\/esm\/(createLucideIcon|defaultAttributes|shared|Icon|icons\/(check|plus|minus|star|copy|cpu|chevron-right|lock)\.)/.test(
      id,
    ) ||
    // What sign-in and the screen its email link opens share, its words and its form's loader; a session's title, shared by Home and
    // the session screen; the listing fields, shared by a machine's price and the link screen. Each would otherwise be another file
    // beside a screen's own, which on a slow connection costs a round trip (ADR 0018).
    /\/src\/features\/(sign-in\/(messages|LazyEmailForm)|flow\/headline|hosting\/PriceForm)\./.test(id) ||
    // The mutation code: shared by Book and the session screen, which would otherwise each wait on another small file (ADR 0021).
    /query-core\/build\/modern\/mutationObserver|react-query\/build\/modern\/useMutation/.test(id) ||
    /node_modules\/(\.pnpm\/)?clsx/.test(id)
  ) {
    return "shell";
  }
  return undefined;
}

/**
 * Which modules the main script holds, and how many bytes of each, for scripts/first-load.ts to say what grew it (ADR 0049,
 * "Watch the main script").
 */
function mainScriptModules(): Plugin {
  const root = fileURLToPath(new URL(".", import.meta.url));
  return {
    name: "idleo-main-script-modules",
    generateBundle(_, bundle) {
      // And every chunk's source modules, by path from web/, for scripts/site-modules.ts to hold each site to its own
      // code (ADR 0058).
      const chunks = Object.values(bundle).flatMap((file) =>
        file.type === "chunk"
          ? [
              [
                file.fileName,
                Object.keys(file.modules).map((id) => id.replace(root, "").replace(/\?.*$/, "")),
              ],
            ]
          : [],
      );
      this.emitFile({
        type: "asset",
        fileName: ".vite/chunk-modules.json",
        source: JSON.stringify(Object.fromEntries(chunks)),
      });
      const shell = Object.values(bundle).find((file) => file.type === "chunk" && file.name === "shell");
      if (shell?.type === "chunk") {
        const sizes = Object.entries(shell.modules).map(([id, module]) => [id, module.renderedLength]);
        this.emitFile({
          type: "asset",
          fileName: ".vite/main-script-modules.json",
          source: JSON.stringify(Object.fromEntries(sizes)),
        });
      }
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, process.cwd(), ["VITE_", "IDLEO_"]), ...process.env } as BuildEnv;
  // Which site this build is (ADR 0058, src/sites/sites.ts): `@site` is its folder, so its routes, bars and screens are the
  // only ones in the bundle.
  const site = siteOf(process.env);
  // A wrong combination (above all, the mock sign-in adapter outside a local build) stops here, before any
  // bundle exists (ADR 0018).
  assertBuildSettings(env);
  return {
    plugins: [react(), tailwindcss(), themeBoot(), mainScriptModules(), galleryPage()],
    resolve: {
      alias: {
        "@site": fileURLToPath(new URL(`./src/sites/${site}`, import.meta.url)),
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    // The adapter not chosen is dead code, so it's left out of the bundle entirely.
    define: {
      "import.meta.env.VITE_AUTH": JSON.stringify(env.VITE_AUTH),
      // Where the app's links to the website (Terms, Privacy, "← idleo") lead.
      "import.meta.env.IDLEO_SITE_URL": JSON.stringify(env.IDLEO_SITE_URL ?? ""),
      // Where each site's links to the others lead ("Rent a GPU", "Earn with your laptop", a renter's PAN, a console notice).
      "import.meta.env.IDLEO_APP_URL": JSON.stringify(env.IDLEO_APP_URL ?? ""),
      "import.meta.env.IDLEO_HOST_URL": JSON.stringify(env.IDLEO_HOST_URL ?? ""),
      "import.meta.env.IDLEO_ADMIN_URL": JSON.stringify(env.IDLEO_ADMIN_URL ?? ""),
      "import.meta.env.IDLEO_WEB_SITE": JSON.stringify(site),
      // The release real visits' vitals and errors name (ADR 0036).
      "import.meta.env.IDLEO_RELEASE": JSON.stringify(process.env.IDLEO_RELEASE ?? "development"),
    },
    build: {
      // Each site's own folder (dist, dist-host, dist-admin); IDLEO_DIST: a second build side by side, as the end-to-end test makes
      // for the Firebase screens.
      outDir: distOf(site, process.env.IDLEO_DIST),
      target: "es2022",
      manifest: true,
      // Source maps are made for every build but never served: scripts/postbuild.ts moves them out of dist into
      // dist-sourcemaps/, which the control plane reads to name a JavaScript error's file and line (ADR 0036).
      sourcemap: process.env.IDLEO_SOURCEMAP === "1" ? true : "hidden",
      // One style sheet, which goes into every page (scripts/postbuild.ts), so no chunk has one to fetch.
      cssCodeSplit: false,
      // No helper that preloads a lazy chunk's imports, nor its table of every chunk's file names, whose hashes barely compress:
      // a page's own chunks are named in its HTML (scripts/postbuild.ts), and the rest are fetched just after its first screen.
      modulePreload: false,
      rollupOptions: { output: { manualChunks: shellChunk } },
    },
    // Each site's development server has its own dependency cache (node_modules/.vite-app, .vite-host, .vite-admin): with
    // one shared, one server re-optimising left the others serving "504 Outdated Optimize Dep" for what they'd loaded.
    // And each optimises up front what its lazily loaded screens import (src/sites/sites.ts, lazyDeps), so opening one
    // never re-optimises mid-session.
    cacheDir: `node_modules/.vite-${site}`,
    optimizeDeps: {
      include: [
        ...SITE_FACTS[site].lazyDeps,
        ...(env.VITE_AUTH === "firebase" ? SIGN_IN_DEPS.firebase : SIGN_IN_DEPS["oidc-mock"]),
      ],
    },
    server: { port: SITE_FACTS[site].port, strictPort: true },
    preview: { port: SITE_FACTS[site].port, strictPort: true },
  };
});
