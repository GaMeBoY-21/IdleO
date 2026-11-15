import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "wouter";
import { RelativeTime } from "@/components/ui/Time";
import { cn } from "@/lib/cn";
import { ADMIN, adminApi, adminData } from "./api";
import { recordHref } from "./links";
import { Empty, PagedView } from "./ui";

const IST = new Intl.DateTimeFormat("en-IN", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Kolkata",
});

/** A kind's name, as the filter shows it. */
const shown = (kind: string) => kind.replace(/_/g, " ");

type Subject = "user" | "host" | "session" | "payment";

/**
 * The one timeline (ADR 0052), on every record's page: what each module knows about this thing, newest first, filtered by
 * kind, with a cursor. Which kinds an admin sees is the server's to say. Times are in IST, with how long ago beside them.
 */
export function Timeline({ subject, id }: { subject: Subject; id: string }) {
  const [only, setOnly] = useState<string[]>([]);
  const events = useInfiniteQuery({
    queryKey: [ADMIN, "timeline", subject, id, only],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/timeline", {
          params: {
            query: {
              subject,
              id,
              ...(only.length > 0 ? { kinds: only } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const kinds = events.data?.pages[0]?.kinds ?? [];
  const unanswered = events.data?.pages[0]?.unanswered ?? [];
  return (
    <div className="flex flex-col gap-2">
      {kinds.length > 1 && (
        <div role="group" aria-label="Show only" className="flex flex-wrap gap-1">
          {kinds.map((kind) => {
            const on = only.includes(kind);
            return (
              <button
                key={kind}
                type="button"
                aria-pressed={on}
                onClick={() => setOnly(on ? only.filter((other) => other !== kind) : [...only, kind])}
                className={cn(
                  "min-h-touch rounded-full px-4 text-caption font-medium md:min-h-9",
                  on ? "bg-surface-4 text-ink" : "bg-surface-3 text-ink-subtle",
                )}
              >
                {shown(kind)}
              </button>
            );
          })}
        </div>
      )}
      {unanswered.length > 0 && (
        <p className="text-caption text-ink-subtle">
          These didn&apos;t answer in time, so there may be more: {unanswered.map(shown).join(", ")}.
        </p>
      )}
      <PagedView
        query={events}
        what="the timeline"
        items={(page) => page.events}
        empty={<Empty title="Nothing yet">What happens to this will be listed here.</Empty>}
      >
        {(rows) => (
          <ol aria-label="Timeline" className="flex flex-col">
            {rows.map((event) => (
              <li
                key={event.key}
                className="flex flex-col gap-1 border-b border-hairline py-2 md:flex-row md:items-baseline md:gap-4"
              >
                <span className="tabular w-48 shrink-0 text-caption text-ink-subtle">
                  {IST.format(new Date(event.at))} IST · <RelativeTime value={event.at} />
                </span>
                <span className="w-24 shrink-0 text-caption text-ink-tertiary">{shown(event.kind)}</span>
                <span className="min-w-0 text-small">
                  {event.page && event.entityId ? (
                    <Link href={recordHref(event.page, event.entityId)} className="font-medium underline">
                      {event.title}
                    </Link>
                  ) : (
                    <span className="font-medium">{event.title}</span>
                  )}
                  {event.detail && <span className="text-ink-subtle"> · {event.detail}</span>}
                </span>
              </li>
            ))}
          </ol>
        )}
      </PagedView>
    </div>
  );
}
