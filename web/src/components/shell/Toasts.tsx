import { lazy, Suspense } from "react";
import { useAfterFirstScreen } from "@/lib/after-first-screen";

const Toaster = lazy(() => import("@/components/ui/Toast").then((module) => ({ default: module.Toaster })));

/** Each frame's toasts: their code loads once the first screen has, so it never holds the first load back. */
export function Toasts() {
  const loaded = useAfterFirstScreen();
  return loaded ? (
    <Suspense fallback={null}>
      <Toaster />
    </Suspense>
  ) : null;
}
