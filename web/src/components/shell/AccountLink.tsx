import { Link, useLocation } from "wouter";
import { useCachedMe } from "@/api/queries";
import { useAuth } from "@/auth";
import { cn } from "@/lib/cn";

/**
 * The account at the foot of the sidebar (web/DESIGN.md "Navigation"): an ion avatar and the person's name, a link to
 * Account, which holds what the phone's "More" menu does. The name is what the app already knows (the account's, or
 * the start of the email); the box is the same whatever has arrived, so nothing moves. The link is named by what it shows
 * ("Asha, account"), so someone who says what they see on screen to a voice control finds it (WCAG 2.5.3).
 */
export function AccountLink() {
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
        "flex min-h-touch min-w-0 flex-1 items-center gap-2 rounded-control px-4 py-2 text-small font-semibold",
        current ? "bg-raised" : "hover:bg-raised",
      )}
    >
      <span
        aria-hidden="true"
        className="flex size-8 shrink-0 items-center justify-center rounded-full bg-ion font-extrabold text-ink"
      >
        {name.charAt(0).toUpperCase()}
      </span>
      <span className="truncate">{name}</span>
      {name !== "Account" && <span className="sr-only">, account</span>}
    </Link>
  );
}
