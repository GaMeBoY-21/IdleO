import { Ellipsis } from "lucide-react";
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
        "flex items-center justify-center rounded-full bg-ion font-extrabold text-ink",
        large ? "size-touch text-body" : "size-10 text-small",
      )}
    >
      {(email ?? "?").charAt(0).toUpperCase()}
    </span>
  );
}

/** The phone's last tab: "More", which opens the menu (Account, My files, Appearance, Sign out). */
export function MoreFace() {
  return (
    <>
      <Ellipsis aria-hidden="true" className="size-5.5" strokeWidth={1.75} />
      <span className="text-caption font-semibold">More</span>
    </>
  );
}

const loadMenu = () => import("./UserMenu");
const UserMenu = lazy(() => loadMenu().then((module) => ({ default: module.UserMenu })));

/**
 * The user menu (Radix's dropdown and its positioning) isn't in the first load, and isn't fetched while the screen
 * is still loading (ADR 0018): it loads once the first screen has, or at once if the person reaches for it first. Until then this is a real button with the same look, which opens the menu when it arrives.
 */
export function UserMenuButton({ tab = false }: { tab?: boolean }) {
  const [wanted, setWanted] = useState<"idle" | "open" | null>(null);

  useEffect(() => afterFirstScreen(() => setWanted((current) => current ?? "idle")), []);

  const placeholder = (
    <button
      type="button"
      className={
        tab
          ? "flex h-full w-full flex-col items-center justify-center gap-0.5 rounded-bar text-text-2"
          : "flex size-touch items-center justify-center rounded-full"
      }
      aria-label={tab ? "More" : "Your account and settings"}
      aria-haspopup="menu"
      onPointerEnter={() => void loadMenu()}
      onFocus={() => void loadMenu()}
      onClick={() => setWanted("open")}
    >
      {tab ? <MoreFace /> : <Avatar />}
    </button>
  );
  if (wanted === null) {
    return placeholder;
  }
  return (
    <Suspense fallback={placeholder}>
      <UserMenu defaultOpen={wanted === "open"} tab={tab} />
    </Suspense>
  );
}
