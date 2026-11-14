import { Link, useLocation } from "wouter";
import { useCachedMe } from "@/api/queries";
import { useAuth } from "@/auth";
import { cn } from "@/lib/cn";

/**
 * The account at the foot of the sidebar (web/DESIGN.md "Navigation"): a small avatar and the person's name, a link to
 * Account, which holds what the phone's "More" menu does. Collapsed to its icons, only the avatar. The name is what the
 * app already knows (the account's, or the start of the email); the box is the same whatever has arrived, so nothing
 * moves. The link is named by what it shows ("Asha, account"), so someone who says what they see on screen to a voice
 * control finds it (WCAG 2.5.3).
 */
export function AccountLink({ collapsed = false }: { collapsed?: boolean }) {
  const [pathname] = useLocation();
  const auth = useAuth();
  const me = useCachedMe();
  const emailName = auth.status === "signed-in" ? auth.user.email?.split("@")[0] : me?.emailName;
  const name = me?.displayName ?? emailName ?? "Account";
  const current = pathname.startsWith("/account");
  return (
    <Link
      href="/account/"
      aria-current={current ? "page" : undefined}
      className={cn(
        "flex min-h-control min-w-0 items-center gap-2 rounded-control px-2 text-button",
        collapsed && "justify-center",
        current ? "bg-surface-3 text-ink" : "text-ink-subtle hover:bg-surface-2 hover:text-ink",
      )}
    >
      <span
        aria-hidden="true"
        className="flex size-5 shrink-0 items-center justify-center rounded-full border border-hairline bg-surface-3 text-caption text-ink"
      >
        {name.charAt(0).toUpperCase()}
      </span>
      <span className={collapsed ? "sr-only" : "truncate"}>{name}</span>
      {name !== "Account" && <span className="sr-only">, account</span>}
    </Link>
  );
}
