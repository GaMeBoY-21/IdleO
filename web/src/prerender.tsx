import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToString } from "react-dom/server";
import { Router } from "wouter";
import { App } from "./App";

/**
 * A screen's first frame as static HTML, written into its index.html at build time (scripts/postbuild.ts, ADR
 * 0018), so it paints before any script has run. Sign-in is complete but for its form, which loads just after;
 * the signed-in screens show their frame until sign-in has said who's here. The app then renders the same
 * markup over it, so nothing moves.
 */
export function render(path: string): string {
  return renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <Router ssrPath={path} ssrSearch="">
        <App />
      </Router>
    </QueryClientProvider>,
  );
}
