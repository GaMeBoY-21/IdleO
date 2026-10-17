import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { isLive, useTemplateName, type Session } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { StateChip } from "@/components/ui/StateChip";
import { Tile } from "@/components/ui/Tile";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { costSoFar } from "@/lib/sessions";
import { formatDuration } from "@/lib/time";

/**
 * One session in a list, as one white tile that is a link (web/DESIGN.md "The session screen"): a lime dot when it is live,
 * its template's whole name, which wraps and is never cut off; its state; when it started, relative; how long it has
 * run, ticking while it's live; and its cost so far, or its final charge.
 */
export function SessionRow({ session }: { session: Session }) {
  const templateName = useTemplateName();
  const live = isLive(session);
  return (
    <li>
      <Link
        href={`/session/?id=${session.id}`}
        className="tile-motion flex min-h-18 items-center gap-3 rounded-tile bg-fill px-4.5 py-4"
      >
        {live && (
          <span
            aria-hidden="true"
            className="size-3 shrink-0 rounded-full bg-live ring-1 ring-on-colour/30"
          />
        )}
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className="text-option font-semibold break-words">{templateName(session.templateId)}</p>
          <p className="text-small text-text-muted">
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
        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <StateChip state={session.state} />
          <p className="text-small tabular">
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

/**
 * The laptop's table of sessions (web/DESIGN.md "Laptop layout"): one white tile, a row for each session as a link,
 * with its columns under a header: what, when it started, how long, its state and what it cost.
 */
export function SessionTable({ sessions }: { sessions: Session[] }) {
  return (
    <Tile as="div" className="flex flex-col px-6 py-2">
      <div
        aria-hidden="true"
        className="flex items-center gap-4 border-b border-divider px-3 py-3 text-caption font-medium text-text-muted"
      >
        <span className="flex-1">Service</span>
        <span className="w-1/5 shrink-0 text-left">Started</span>
        <span className="w-1/6 shrink-0 text-left">Time</span>
        <span className="w-1/6 shrink-0">State</span>
        <span className="w-1/6 shrink-0 text-right">Charge</span>
        <span className="size-5 shrink-0" />
      </div>
      <ul className="flex flex-col divide-y divide-divider">
        {sessions.map((session) => (
          <TableRow key={session.id} session={session} />
        ))}
      </ul>
    </Tile>
  );
}

function TableRow({ session }: { session: Session }) {
  const templateName = useTemplateName();
  const live = isLive(session);
  return (
    <li>
      <Link
        href={`/session/?id=${session.id}`}
        className="flex min-h-18 items-center gap-4 rounded-control px-3 py-3 hover:bg-ground"
      >
        <span className="flex min-w-0 flex-1 items-center gap-3">
          {live && (
            <span
              aria-hidden="true"
              className="size-3 shrink-0 rounded-full bg-live ring-1 ring-on-colour/30"
            />
          )}
          <span className="text-option font-semibold break-words">{templateName(session.templateId)}</span>
        </span>
        <span className="w-1/5 shrink-0 text-left text-small text-text-muted">
          {session.startedAt ? (
            <RelativeTime value={session.startedAt} />
          ) : (
            <RelativeTime value={session.createdAt} prefix="Booked" />
          )}
        </span>
        <span className="w-1/6 shrink-0 text-left text-small tabular">
          {session.startedAt ? (
            <>
              <span className="sr-only">{live ? "running for" : "ran for"} </span>
              {live ? (
                <Elapsed from={session.startedAt} />
              ) : (
                formatDuration(session.startedAt, session.endedAt ?? session.startedAt)
              )}
            </>
          ) : (
            "–"
          )}
        </span>
        <span className="flex w-1/6 shrink-0 items-center">
          <StateChip state={session.state} />
        </span>
        <p className="w-1/6 shrink-0 text-right text-small tabular">
          <span className="sr-only">{live ? "Cost so far" : "Charged"} </span>
          <Money paise={live ? costSoFar(session) : session.chargedPaise} className="font-semibold" />
          {live && <span className="text-text-muted"> so far</span>}
        </p>
        <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-text-muted" strokeWidth={1.75} />
      </Link>
    </li>
  );
}

export function SessionList({ sessions }: { sessions: Session[] }) {
  return (
    <ul className="flex flex-col gap-2.5">
      {sessions.map((session) => (
        <SessionRow key={session.id} session={session} />
      ))}
    </ul>
  );
}

/** Rows of exactly a loaded row's height, so the list arriving moves nothing below it. */
export function SessionRowsSkeleton({ rows = 3, table = false }: { rows?: number; table?: boolean }) {
  if (table) {
    return (
      <Tile as="div" className="flex flex-col px-6 py-2">
        <div aria-hidden="true" className="min-h-10 border-b border-divider" />
        {Array.from({ length: rows }, (_, key) => (
          <div
            key={key}
            className="flex min-h-18 items-center gap-4 border-b border-divider px-3 py-3 last:border-0"
          >
            <SkeletonLine size="option" width="w-1/3" />
          </div>
        ))}
      </Tile>
    );
  }
  return (
    <div className="flex flex-col gap-2.5">
      {Array.from({ length: rows }, (_, key) => (
        <div key={key} className="flex min-h-18 items-center gap-3 rounded-tile bg-fill px-4.5 py-4">
          <div className="flex flex-1 flex-col gap-0.5">
            <SkeletonLine size="option" width="w-1/2" />
            <SkeletonLine size="small" width="w-2/3" />
          </div>
          <Skeleton className="h-14 w-1/4" />
          <span aria-hidden="true" className="size-5 shrink-0" />
        </div>
      ))}
    </div>
  );
}
