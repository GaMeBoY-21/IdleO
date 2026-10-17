import { Link } from "wouter";
import { isLive, type Session } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { Elapsed } from "@/components/ui/Time";
import { offeredServices, serviceOf } from "@/lib/catalog";
import { cn } from "@/lib/cn";
import { gpuName } from "@/lib/gpu";
import { bookFrom, costSoFar } from "@/lib/sessions";
import { formatDuration } from "@/lib/time";

type Catalog = components["schemas"]["TemplateList"];

/** How many sessions the table shows. */
export const RECENT = 4;

/** "Fine-tune a model · Standard": the service, and the preset it was run with when it had one. */
export function sessionLabel(session: Session, catalog: Catalog): string {
  const service = serviceOf(catalog, session.templateId)?.name;
  const template = catalog.templates.find((candidate) => candidate.id === session.templateId);
  const chosen = Object.entries(session.presets)
    .map(
      ([name, id]) =>
        template?.questions
          .find((question) => question.name === name)
          ?.options.find((option) => option.id === id)?.label,
    )
    .find((label) => label !== undefined);
  return [service ?? template?.name ?? session.templateId, chosen].filter(Boolean).join(" · ");
}

/** The row's grid: what, GPU, ran, cost and the action. */
const COLUMNS = "cols-recent grid items-center gap-x-3";

/**
 * "Recent sessions", the white tile under the services (docs/design/bento/laptop/home-laptop.html): a table of the last
 * few, with what each was, its GPU, how long it ran and what it cost, and "Run again". A live one shows a lime dot, its
 * time ticking and its cost so far, and "Open": on a laptop it replaces the phone's "Running now" tile. Run again
 * is offered only for services Home shows. With none, it says so and shows no table.
 */
export function RecentSessions({ sessions, catalog }: { sessions: Session[]; catalog: Catalog }) {
  const shown = sessions.slice(0, RECENT);
  const offered = new Set(offeredServices(catalog).map((service) => service.id));
  return (
    <Tile
      aria-labelledby="recent-title"
      size="lg"
      className="flex min-h-0 flex-col gap-1.5 md:col-span-6 xl:col-span-8 xl:px-7 xl:py-6"
    >
      <div className="flex items-baseline justify-between pb-1.5">
        <h2 id="recent-title" className="text-section">
          Recent sessions
        </h2>
        <Link href="/sessions/" className="text-small font-bold hover:underline">
          All sessions ›
        </Link>
      </div>
      {shown.length === 0 ? (
        <p className="py-6 text-body text-text-muted">No sessions yet. Pick a service above to start.</p>
      ) : (
        <div role="table" aria-label="Recent sessions" className="flex flex-col">
          <div
            role="row"
            className={cn(COLUMNS, "border-b border-divider py-2 text-caption text-text-muted")}
          >
            <span role="columnheader">What</span>
            <span role="columnheader">GPU</span>
            <span role="columnheader">Ran</span>
            <span role="columnheader">Cost</span>
            <span role="columnheader" className="sr-only">
              Action
            </span>
          </div>
          {shown.map((session) => {
            const live = isLive(session);
            const service = serviceOf(catalog, session.templateId);
            const again = !live && service !== undefined && offered.has(service.id);
            return (
              <div
                key={session.id}
                role="row"
                className={cn(COLUMNS, "border-b border-divider py-2.5 text-body tabular last:border-b-0")}
              >
                <Link
                  role="cell"
                  href={`/session/?id=${session.id}`}
                  className="flex min-w-0 items-center gap-2.5 font-bold hover:underline"
                >
                  {live && (
                    <span
                      aria-hidden="true"
                      className="size-2.5 shrink-0 rounded-full bg-live ring-1 ring-on-colour/30 motion-safe:animate-pulse"
                    />
                  )}
                  <span className="truncate">{sessionLabel(session, catalog)}</span>
                </Link>
                <span role="cell" className="truncate text-text-muted">
                  {session.gpu ? gpuName(session.gpu.model) : "—"}
                </span>
                <span role="cell">
                  {session.startedAt ? (
                    live ? (
                      <Elapsed from={session.startedAt} />
                    ) : (
                      formatDuration(session.startedAt, session.endedAt ?? session.startedAt)
                    )
                  ) : (
                    "—"
                  )}
                </span>
                <span role="cell">
                  <Money paise={live ? costSoFar(session) : session.chargedPaise} />
                  {live && <span className="sr-only"> so far</span>}
                </span>
                <span role="cell" className="flex justify-end">
                  {live ? (
                    <Link
                      href={`/session/?id=${session.id}`}
                      className="flex min-h-touch items-center rounded-full bg-ground px-3.5 text-small font-bold hover:bg-divider"
                    >
                      Open
                    </Link>
                  ) : again ? (
                    <Link
                      href={bookFrom(session)}
                      className="flex min-h-touch items-center rounded-full bg-ground px-3.5 text-small font-bold hover:bg-divider"
                    >
                      Run again
                    </Link>
                  ) : null}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </Tile>
  );
}
