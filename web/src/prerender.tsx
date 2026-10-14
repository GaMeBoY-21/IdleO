import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToString } from "react-dom/server";
import { Router } from "wouter";
import { App, preloadScreen } from "./App";

/**
 * A screen's first frame as static HTML, written into its index.html at build time (scripts/postbuild.ts, ADR
 * 0018), so it paints before any script has run. Sign-in is complete but for its form, which loads just after;
 * the signed-in screens show their frame until sign-in has said who's here. The app then renders the same
 * markup over it, so nothing moves.
 *
 * The screen a page opens on is loaded first, as the browser loads it before hydrating (src/main.tsx): otherwise a
 * screen in its own chunk would suspend here, the HTML would hold a boundary the server couldn't finish, and the
 * browser would throw away that part of the page and draw it again (React error 419).
 */
export async function render(path: string): Promise<string> {
  await preloadScreen(path);
  return renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <Router ssrPath={path} ssrSearch="">
        <App />
      </Router>
    </QueryClientProvider>,
  );
}
