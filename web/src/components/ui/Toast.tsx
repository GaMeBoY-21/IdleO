import { useSyncExternalStore } from "react";
import { cn } from "@/lib/cn";
import { dismiss, shownToasts, subscribe } from "@/lib/toast";

/**
 * Toasts on screen (web/DESIGN.md "Feedback"), over the page's foot without covering what's being read: appearing in
 * 150 ms, not at all under reduced motion. Loaded with the first toast, and heard through the frame's live region
 * (components/shell/Toasts.tsx); the store is lib/toast.ts.
 */
export function Toaster({ className }: { className?: string }) {
  const toasts = useSyncExternalStore(subscribe, shownToasts, shownToasts);
  return (
    // Heard through the frame's own live region (components/shell/Toasts.tsx), which is there before any toast is.
    <div
      className={cn(
        "pointer-events-none fixed inset-x-4 bottom-24 z-toast flex flex-col items-center gap-2 md:bottom-8",
        className,
      )}
    >
      {toasts.map((item) => (
        <div
          key={item.id}
          className="appear pointer-events-auto flex min-h-touch w-full max-w-narrow items-center gap-4 rounded-control bg-ink px-4 py-2 text-small text-on-night scope-night"
        >
          <span className="min-w-0 flex-1">{item.message}</span>
          {item.undo && (
            <button
              type="button"
              className="inline-flex min-h-touch items-center px-2 font-semibold text-volt underline-offset-4 hover:underline"
              onClick={() => {
                item.undo?.();
                dismiss(item.id);
              }}
            >
              Undo
            </button>
          )}
          <button
            type="button"
            aria-label="Dismiss"
            className="inline-flex size-touch shrink-0 items-center justify-center text-on-night-2 hover:text-on-night"
            onClick={() => dismiss(item.id)}
          >
            <svg
              aria-hidden="true"
              viewBox="0 0 16 16"
              className="size-4"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <path d="M4 4l8 8M12 4l-8 8" strokeLinecap="round" />
            </svg>
          </button>
        </div>
      ))}
    </div>
  );
}
