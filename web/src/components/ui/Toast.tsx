import { useSyncExternalStore } from "react";
import { cn } from "@/lib/cn";

/**
 * Toasts (web/DESIGN.md "Feedback"): a short line that an action worked, with Undo where the action can be undone, for 5
 * seconds (8 with Undo), over the page's foot without covering what's being read. A problem is never a toast: it's said
 * where it happened. One `Toaster` in each frame shows them; any screen calls `toast()`. They appear in 150 ms, not at all
 * under reduced motion, and screen readers hear them (a polite status).
 */
export interface ToastInput {
  message: string;
  /** Undoes what was just done; the toast says "Undo" and goes once it's pressed. */
  undo?: () => void;
}
interface Shown extends ToastInput {
  id: number;
}

let shown: readonly Shown[] = [];
let next = 1;
const listeners = new Set<() => void>();
const publish = (toasts: readonly Shown[]) => {
  shown = toasts;
  listeners.forEach((listener) => listener());
};

export function dismiss(id: number): void {
  publish(shown.filter((toast) => toast.id !== id));
}

export function toast(input: ToastInput): number {
  const id = next++;
  // At most three at once: the oldest goes first.
  publish([...shown.slice(-2), { ...input, id }]);
  setTimeout(() => dismiss(id), input.undo ? 8_000 : 5_000);
  return id;
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export function Toaster({ className }: { className?: string }) {
  const toasts = useSyncExternalStore(
    subscribe,
    () => shown,
    () => shown,
  );
  return (
    <div
      role="status"
      aria-live="polite"
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
