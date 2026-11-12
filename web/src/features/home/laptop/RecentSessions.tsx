import { Link } from "wouter";
import { isLive, type Session } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { DataCell, DataRow, DataTable } from "@/components/ui/DataTable";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { Elapsed } from "@/components/ui/Time";
import { offeredServices, serviceOf } from "@/lib/catalog";
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

/**
 * "Recent sessions", a panel under the credit (approved reference `AppHome`): a table of the last
 * few, with what each was, its GPU, how long it ran and what it cost, and "Run again". A live one shows a lime dot, its
 * time ticking and its cost so far, and "Open": on a laptop it replaces the phone's "Running now" tile. Run again
 * is offered only for services Home shows. With none, it says so and shows no table.
 */
export function RecentSessions({ sessions, catalog }: { sessions: Session[]; catalog: Catalog }) {
  const shown = sessions.slice(0, RECENT);
  const offered = new Set(offeredServices(catalog).map((service) => service.id));
  return (
    <Tile aria-labelledby="recent-title" size="lg" className="flex min-h-0 flex-col gap-1">
      <div className="flex items-baseline justify-between pb-2">
        <h2 id="recent-title" className="text-section">
          Recent sessions
        </h2>
        {shown.length > 0 && (
          <Link
            href="/sessions/"
            className="text-small font-semibold text-link hover:text-link-hover hover:underline"
          >
            All sessions ›
          </Link>
        )}
      </div>
      {shown.length === 0 ? (
        <p className="py-6 text-body text-text-2">No sessions yet. Pick a service above to start.</p>
      ) : (
        <DataTable
          caption="Recent sessions"
          columns={[
            { label: "What" },
            { label: "GPU" },
            { label: "Ran", numeric: true },
            { label: "Cost", numeric: true },
            { label: "Action", hidden: true },
          ]}
        >
          {shown.map((session) => {
            const live = isLive(session);
            const service = serviceOf(catalog, session.templateId);
            const again = !live && service !== undefined && offered.has(service.id);
            const label = sessionLabel(session, catalog);
            return (
              <DataRow key={session.id} className="text-body">
                <DataCell className="max-w-0 w-2/5">
                  <Link
                    href={`/session/?id=${session.id}`}
                    title={label}
                    className="flex min-w-0 items-center gap-2 font-bold hover:underline"
                  >
                    {live && (
                      <span
                        aria-hidden="true"
                        className="size-2.5 shrink-0 rounded-full bg-running-dot ring-1 ring-ink/30 motion-safe:animate-pulse"
                      />
                    )}
                    <span className="truncate">{label}</span>
                  </Link>
                </DataCell>
                <DataCell className="max-w-0 truncate text-text-2">
                  {session.gpu ? gpuName(session.gpu.model) : "—"}
                </DataCell>
                <DataCell numeric>
                  {session.startedAt ? (
                    live ? (
                      <Elapsed from={session.startedAt} />
                    ) : (
                      formatDuration(session.startedAt, session.endedAt ?? session.startedAt)
                    )
                  ) : (
                    "—"
                  )}
                </DataCell>
                <DataCell numeric>
                  <Money paise={live ? costSoFar(session) : session.chargedPaise} />
                  {live && <span className="sr-only"> so far</span>}
                </DataCell>
                <DataCell className="text-right">
                  {live ? (
                    <Link
                      href={`/session/?id=${session.id}`}
                      className="inline-flex min-h-touch items-center rounded-control bg-panel-2 px-4 text-small font-bold hover:bg-line"
                    >
                      Open
                    </Link>
                  ) : again ? (
                    <Link
                      href={bookFrom(session)}
                      className="inline-flex min-h-touch items-center rounded-control bg-panel-2 px-4 text-small font-bold hover:bg-line"
                    >
                      Run again
                    </Link>
                  ) : null}
                </DataCell>
              </DataRow>
            );
          })}
        </DataTable>
      )}
    </Tile>
  );
}
