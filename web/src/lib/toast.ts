/**
 * The toasts' store (web/DESIGN.md "Feedback"): any screen calls `toast()`; the frame's `Toasts` says them at once and
 * shows them with code loaded on the first (components/ui/Toast.tsx). A short line that an action worked,
 * with Undo where it can be undone, for 5 seconds (8 with Undo). A problem is never a toast.
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

export const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export const shownToasts = () => shown;
