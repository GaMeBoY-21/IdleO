import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { isLive, useTemplateName, type Session } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { StateChip } from "@/components/ui/StateChip";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { costSoFar } from "@/lib/sessions";
import { formatDuration } from "@/lib/time";

/**
 * One session in a list, as one link (web/DESIGN.md): its template's whole name, which wraps and is never cut off;
 * its state; when it started, relative; how long it has run, ticking while it's live; and its cost so far, or its
 * final charge.
 */
export function SessionRow({ session }: { session: Session }) {
  const templateName = useTemplateName();
  const live = isLive(session);
  return (
    <li>
      <Link
        href={`/session/?id=${session.id}`}
        className="flex min-h-16 items-center gap-3 px-3 py-3 hover:bg-sunken md:px-4"
      >
        <div className="min-w-0 flex-1">
          <p className="text-body font-medium break-words">{templateName(session.templateId)}</p>
          <p className="text-caption text-text-muted">
            {session.startedAt ? (
              <>
                <RelativeTime value={session.startedAt} prefix="Started" />
                {" · "}
                <span className="sr-only">{live ? "running for" : "ran for"} </span>
                {live ? (
                  <Elapsed from={session.startedAt} />
                ) : (
                  <span className="tabular">
                    {formatDuration(session.startedAt, session.endedAt ?? session.startedAt)}
                  </span>
                )}
              </>
            ) : (
              <RelativeTime value={session.createdAt} prefix="Booked" />
            )}
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <StateChip state={session.state} />
          <p className="text-small">
            <span className="sr-only">{live ? "Cost so far" : "Charged"} </span>
            <Money paise={live ? costSoFar(session) : session.chargedPaise} className="font-semibold" />
            {live && <span className="text-text-muted"> so far</span>}
          </p>
        </div>
        <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-text-muted" strokeWidth={1.75} />
      </Link>
    </li>
  );
}

export function SessionList({ sessions }: { sessions: Session[] }) {
  return (
    <ul className="divide-y divide-border rounded-control border border-border">
      {sessions.map((session) => (
        <SessionRow key={session.id} session={session} />
      ))}
    </ul>
  );
}

/** Rows of exactly a loaded row's height, so the list arriving moves nothing below it. */
export function SessionRowsSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="divide-y divide-border rounded-control border border-border">
      {Array.from({ length: rows }, (_, key) => (
        <div key={key} className="flex min-h-16 items-center gap-3 px-3 py-3 md:px-4">
          <div className="flex-1">
            <Skeleton className="h-6 w-1/2" />
            <Skeleton className="mt-1 h-4 w-2/3" />
          </div>
          <Skeleton className="h-12 w-1/4" />
        </div>
      ))}
    </div>
  );
}
