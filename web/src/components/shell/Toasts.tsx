import { lazy, Suspense, useSyncExternalStore } from "react";
import { shownToasts, subscribe } from "@/lib/toast";

const Toaster = lazy(() => import("@/components/ui/Toast").then((module) => ({ default: module.Toaster })));

/**
 * Each frame's toasts. What's said is heard at once through a live region that's always here and costs nothing; the
 * toasts' own code loads only when the first one is shown, so nothing about them is fetched while a screen loads.
 */
export function Toasts() {
  const toasts = useSyncExternalStore(subscribe, shownToasts, shownToasts);
  const latest = toasts.at(-1);
  return (
    <>
      <p role="status" aria-live="polite" className="sr-only">
        {latest?.message ?? ""}
      </p>
      {toasts.length > 0 && (
        <Suspense fallback={null}>
          <Toaster />
        </Suspense>
      )}
    </>
  );
}
