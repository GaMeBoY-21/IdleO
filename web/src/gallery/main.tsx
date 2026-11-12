import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Gallery } from "./Gallery";
import "../styles.css";

/**
 * The component gallery's entry (gallery.html), on a development server only: every shared component in every variant
 * and state, so a pattern is checked, and fixed, once. Nothing here is in any build (scripts/site-modules.ts).
 */
// `?theme=dark` or `?theme=light`: the theme each half of `?both` shows.
const theme = new URLSearchParams(location.search).get("theme");
if (theme === "dark" || theme === "light") {
  document.documentElement.dataset.theme = theme;
}
const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={client}>
      <Gallery />
    </QueryClientProvider>
  </StrictMode>,
);
