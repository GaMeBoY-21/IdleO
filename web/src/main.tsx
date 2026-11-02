import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import { keptUser } from "@/api/persisted-cache";
import { createQueryClient } from "@/api/queries";
import { App, preloadScreen } from "@/App";
import { authStore } from "@/auth";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { SignedOutCacheReset } from "@/components/shell/SignedOutCacheReset";
import { config } from "@/config";
import { afterFirstScreen, afterPaint } from "@/lib/after-first-screen";
import { loadProblemWords } from "@/lib/problems";
import { holdEarlyErrors } from "@/telemetry/early-errors";
import "./styles.css";

// JavaScript errors from the start, and what real visits meet, reported once the first screen has loaded, so the
// reporting never slows the page it measures (ADR 0036).
holdEarlyErrors();
afterFirstScreen(() => {
  void import("@/telemetry/report").then(({ startReporting }) =>
    startReporting({ api: config.apiUrl, app: "app", release: config.release }),
  );
});

afterFirstScreen(() => void loadProblemWords());

const queryClient = createQueryClient();
// The sign-in adapter isn't needed to paint a screen with figures kept from an earlier visit, so it loads just after that
// paint. Anything that needs a token before then, or a visit with nothing kept, has it fetched at once (src/auth/store.ts).
void keptUser().then((uid) => (uid === null ? void authStore.start() : afterPaint(() => void authStore.start())));

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
// The screen this page opens on is loaded first (its code is already preloading, src/screens.ts), so it's drawn
// without waiting on React's Suspense (src/App.tsx).
const root = document.getElementById("root")!;
void preloadScreen(window.location.pathname).then(() => {
  if (window.location.search === "" && root.firstChild) {
    hydrateRoot(root, app);
  } else {
    createRoot(root).render(app);
  }
});
