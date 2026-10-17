import { Link, useLocation } from "wouter";
import { Avatar } from "./UserMenuButton";
import { cn } from "@/lib/cn";

/**
 * From 768 px the avatar is a link to Account, not a menu (web/DESIGN.md "Laptop layout"): Account holds what the menu
 * did. On Account it has a 3 px lime ring, its "current" state.
 */
export function AccountLink() {
  const [pathname] = useLocation();
  const current = pathname.startsWith("/account");
  return (
    <Link
      href="/account/"
      aria-label="Account"
      aria-current={current ? "page" : undefined}
      className={cn(
        "hidden size-touch items-center justify-center rounded-full md:flex",
        current && "ring-3 ring-live",
      )}
    >
      <Avatar large />
    </Link>
  );
}
