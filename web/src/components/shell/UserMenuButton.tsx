import { lazy, Suspense, useEffect, useState } from "react";
import { useAuth } from "@/auth";
import { cn } from "@/lib/cn";
import { afterFirstScreen } from "@/lib/after-first-screen";

/** The avatar the menu opens from, drawn the same before and after the menu's code arrives, so nothing moves. */
export function Avatar({ large = false }: { large?: boolean }) {
  const auth = useAuth();
  const email = auth.status === "signed-in" ? auth.user.email : null;
  return (
    <span
      className={cn(
        "flex items-center justify-center rounded-full bg-primary font-semibold text-primary-fg",
        large ? "size-touch text-body font-bold" : "size-10 text-small",
      )}
    >
      {(email ?? "?").charAt(0).toUpperCase()}
    </span>
  );
}

const loadMenu = () => import("./UserMenu");
const UserMenu = lazy(() => loadMenu().then((module) => ({ default: module.UserMenu })));

/**
 * The user menu (Radix's dropdown and its positioning) isn't in the first load, and isn't fetched while the screen
 * is still loading (ADR 0018): it loads once the first screen has, or at once if the person reaches for it first. Until then this is a real button with the same look, which opens the menu when it arrives.
 */
export function UserMenuButton() {
  const [wanted, setWanted] = useState<"idle" | "open" | null>(null);

  useEffect(() => afterFirstScreen(() => setWanted((current) => current ?? "idle")), []);

  const placeholder = (
    <button
      type="button"
      className="flex size-touch items-center justify-center rounded-full"
      aria-label="Your account and settings"
      aria-haspopup="menu"
      onPointerEnter={() => void loadMenu()}
      onFocus={() => void loadMenu()}
      onClick={() => setWanted("open")}
    >
      <Avatar />
    </button>
  );
  if (wanted === null) {
    return placeholder;
  }
  return (
    <Suspense fallback={placeholder}>
      <UserMenu defaultOpen={wanted === "open"} />
    </Suspense>
  );
}
