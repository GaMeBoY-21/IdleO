import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { itemsOf, useInbox, useMarkRead, type InboxItem } from "@/api/inbox";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { useAfterFirstScreen } from "@/lib/after-first-screen";

/** More than this many at once, and the rest are in the list. */
const MOST_SHOWN = 3;

/**
 * Banners for messages that arrive while the app is open (ADR 0026): what was already waiting when the page opened
 * is in the list and the bell, not shouted again. They sit in the frame under the header, so they push the screen
 * down and never cover it (web/DESIGN.md "Motion"). Opening one goes to what it's about and marks it read;
 * dismissing only hides it, so it stays unread in the list.
 */
export function NotificationBanners() {
  // Nothing at all until the first screen has loaded: no query, no timer, nothing for its first paint to wait behind.
  return useAfterFirstScreen() ? <Arrivals /> : null;
}

function Arrivals() {
  const [pathname] = useLocation();
  const inbox = useInbox();
  const markRead = useMarkRead();
  const seen = useRef<Set<string> | null>(null);
  const [arrivals, setArrivals] = useState<InboxItem[]>([]);
  const [dismissed, setDismissed] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => {
    if (!inbox.data) {
      return;
    }
    const items = itemsOf(inbox.data);
    if (seen.current === null) {
      seen.current = new Set(items.map((item) => item.id));
      return;
    }
    const fresh = items.filter((item) => !item.read && !seen.current!.has(item.id));
    if (fresh.length > 0) {
      fresh.forEach((item) => seen.current!.add(item.id));
      setArrivals((now) => [...fresh, ...now]);
    }
  }, [inbox.data]);

  // Read since (in the list, or another tab): nothing more to say about it.
  const unread = new Set(
    itemsOf(inbox.data)
      .filter((item) => !item.read)
      .map((item) => item.id),
  );
  const showing = arrivals.filter((item) => unread.has(item.id) && !dismissed.has(item.id));
  if (showing.length === 0 || pathname === "/notifications/") {
    return null;
  }
  return (
    <div className="flex flex-col gap-2 px-4 pt-2 md:px-8" aria-label="New notifications" role="region">
      {showing.slice(0, MOST_SHOWN).map((item) => (
        <Banner
          key={item.id}
          tone="info"
          action={
            <Button
              variant="secondary"
              aria-label={`Dismiss: ${item.title}`}
              onClick={() => setDismissed((now) => new Set(now).add(item.id))}
            >
              Dismiss
            </Button>
          }
        >
          <Link
            href={item.link}
            className="font-semibold underline"
            onClick={() => markRead.mutate({ id: item.id })}
          >
            {item.title}
          </Link>
          <span className="block text-text-muted">{item.body}</span>
        </Banner>
      ))}
      {showing.length > MOST_SHOWN && (
        <p className="text-small text-text-muted">
          <Link href="/notifications/" className="underline">
            {showing.length - MOST_SHOWN} more in your notifications
          </Link>
        </p>
      )}
    </div>
  );
}
