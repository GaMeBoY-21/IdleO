import { Bell } from "lucide-react";
import { Link } from "wouter";
import { unreadOf, useInbox } from "@/api/inbox";
import { useCachedMe } from "@/api/queries";
import { useAfterFirstScreen } from "@/lib/after-first-screen";

/** More than this many unread shows as "9+": the badge is a hint, the list is the count. */
const MOST_SHOWN = 9;

/**
 * The bell in the header: a link to the list of messages, with how many are unread. Its box is the same with or
 * without a badge, so the count arriving moves nothing. Until the first screen has loaded it is only the link: no
 * query, no timer, nothing for the screen's first paint to wait behind (ADR 0018); then the count (what the account
 * last said, until the list's own arrives).
 */
export function NotificationBell() {
  return useAfterFirstScreen() ? <CountedBell /> : <BellLink unread={0} />;
}

function CountedBell() {
  const inbox = useInbox();
  const me = useCachedMe();
  return <BellLink unread={unreadOf(inbox.data) ?? me.data?.unreadNotifications ?? 0} />;
}

function BellLink({ unread }: { unread: number }) {
  return (
    <Link
      href="/notifications/"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
      className="relative flex size-touch items-center justify-center rounded-full text-text-2 hover:bg-raised hover:text-text"
    >
      <Bell aria-hidden="true" className="size-5.5" strokeWidth={1.75} />
      {unread > 0 && (
        <span
          aria-hidden="true"
          className="absolute top-1 right-1 flex h-4.5 min-w-4.5 items-center justify-center rounded-full bg-primary px-1 text-caption font-semibold text-primary-fg tabular"
        >
          {unread > MOST_SHOWN ? `${MOST_SHOWN}+` : unread}
        </span>
      )}
    </Link>
  );
}
