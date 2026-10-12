import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { itemsOf, unreadOf, useInbox, useMarkRead, type InboxItem } from "@/api/inbox";
import { Page } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/Button";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { useAfterPaint } from "@/lib/after-first-screen";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import { useTitle } from "@/lib/title";

/** A row is as tall whatever its words (its body always has room for two lines), so the list is its skeleton's height. */
const ROW = "flex items-start gap-3 py-4";

/**
 * Notifications (ADR 0026): what the sessions and machines have told the person, newest first. Opening one goes to
 * what it's about and marks it read (and, with it, the older ones); "Mark all read" marks the lot. Unread ones are
 * bold, with a dot that also says so in words for a screen reader.
 */
export function Notifications() {
  useTitle("Notifications");
  // The list isn't what the screen paints first, so it's asked for once it has painted: a request made before the
  // first paint counts against the speed budgets (ADR 0018).
  const inbox = useInbox(useAfterPaint());
  const markRead = useMarkRead();
  const items = itemsOf(inbox.data);
  const unread = unreadOf(inbox.data) ?? 0;
  const since = staleSince(inbox);

  return (
    <Page title="Notifications">
      {since !== null && <StaleNotice updatedAt={since} />}
      {/* Drawn with the screen, not with the list: it is the largest thing there is at first paint (ADR 0018). */}
      <p className="-mt-4 max-w-prose text-small text-text-muted">
        What your sessions and machines have told you, newest first. The same words go by email, for the kinds
        you&apos;ve left on:{" "}
        <Link href="/account/" className="underline">
          choose them in Account
        </Link>
        .
      </p>
      <section aria-labelledby="notifications-title" className="flex flex-col gap-1">
        <div className="flex min-h-touch items-center justify-between gap-3">
          <h2 id="notifications-title" className="text-section font-bold">
            {unread > 0 ? `${unread} unread` : "All read"}
          </h2>
          <Button
            variant="secondary"
            disabled={unread === 0 || items.length === 0}
            busy={markRead.isPending}
            onClick={() => items[0] && markRead.mutate(items[0].id)}
          >
            Mark all read
          </Button>
        </div>
        {markRead.isError && (
          <p className="text-small text-danger" role="alert">
            {problemMessage(markRead.error)}
          </p>
        )}
        {inbox.data ? (
          <div className="flex flex-col gap-4">
            {items.length === 0 ? (
              <EmptyState title="Nothing yet">
                When a session finishes, stops or can&apos;t start, or a machine of yours goes offline during
                one, it will be here, and by email if you&apos;ve left that on in Account.
              </EmptyState>
            ) : (
              <ul className="flex flex-col divide-y divide-divider">
                {items.map((item) => (
                  <Row key={item.id} item={item} onOpen={() => !item.read && markRead.mutate(item.id)} />
                ))}
              </ul>
            )}
            {inbox.hasNextPage && (
              <Button
                variant="secondary"
                className="self-center"
                busy={inbox.isFetchingNextPage}
                onClick={() => void inbox.fetchNextPage()}
              >
                Show older
              </Button>
            )}
            {inbox.isFetchNextPageError && (
              <p className="text-small text-danger" role="alert">
                We couldn&apos;t load older notifications. Try again.
              </p>
            )}
          </div>
        ) : inbox.isError ? (
          <ErrorState
            error={inbox.error}
            what="your notifications"
            onRetry={() => void inbox.refetch()}
            retrying={inbox.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-divider"
            role="status"
            aria-label="Loading your notifications"
          >
            {[0, 1, 2].map((key) => (
              <div key={key} className={ROW}>
                <span aria-hidden="true" className="mt-1.5 size-2 shrink-0" />
                <div className="flex flex-1 flex-col gap-0.5">
                  <SkeletonLine size="option" width="w-1/2" />
                  <div className="min-h-10">
                    <SkeletonLine size="small" width="w-full" />
                    <SkeletonLine size="small" width="w-2/3" />
                  </div>
                  <SkeletonLine size="caption" width="w-1/4" />
                </div>
                <span aria-hidden="true" className="size-5 shrink-0" />
              </div>
            ))}
          </div>
        )}
      </section>
    </Page>
  );
}

/** One message; as a whole, the link to what it's about. */
function Row({ item, onOpen }: { item: InboxItem; onOpen: () => void }) {
  return (
    <li>
      <Link href={item.link} onClick={onOpen} className={cn(ROW, "hover:bg-fill")}>
        <span
          aria-hidden="true"
          className={cn("mt-2 size-2 shrink-0 rounded-full", item.read ? "bg-transparent" : "bg-primary")}
        />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className={cn("truncate text-option", item.read ? "font-medium" : "font-bold")}>
            {item.read ? null : <span className="sr-only">Unread: </span>}
            {item.title}
          </p>
          <p className="line-clamp-2 min-h-10 text-small text-text-muted">{item.body}</p>
          <p className="text-caption text-text-muted first-letter:uppercase">
            <RelativeTime value={item.createdAt} />
          </p>
        </div>
        <ChevronRight
          aria-hidden="true"
          className="mt-1 size-5 shrink-0 text-text-muted"
          strokeWidth={1.75}
        />
      </Link>
    </li>
  );
}
