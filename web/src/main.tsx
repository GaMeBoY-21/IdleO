import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import { createQueryClient } from "@/api/queries";
import { App } from "@/App";
import { authStore } from "@/auth";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { SignedOutCacheReset } from "@/components/shell/SignedOutCacheReset";
import "./styles.css";

const queryClient = createQueryClient();
// The sign-in adapter isn't needed to paint the first screen, so it loads just after it; anything that needs a
// token waits for it (src/auth/store.ts).
requestAnimationFrame(() => setTimeout(() => void authStore.start(), 0));

const app = (
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SignedOutCacheReset />
      <ErrorBoundary>
        <App />
      </ErrorBoundary>
    </QueryClientProvider>
  </StrictMode>
);

// Each page holds its screen's first frame, pre-rendered without a query string (src/prerender.tsx). With no query,
// the app adopts that HTML as it stands, so what painted first stays; with one, the frame may differ, so the app
// draws the screen afresh over it.
const root = document.getElementById("root")!;
if (window.location.search === "" && root.firstChild) {
  hydrateRoot(root, app);
} else {
  createRoot(root).render(app);
}
