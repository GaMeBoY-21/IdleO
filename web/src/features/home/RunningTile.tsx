import type { Session } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { Money } from "@/components/ui/Money";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { Elapsed } from "@/components/ui/Time";
import { headline } from "@/features/flow/headline";
import { serviceOf } from "@/lib/catalog";
import { cn } from "@/lib/cn";
import { gpuName } from "@/lib/gpu";
import { costSoFar } from "@/lib/sessions";

type Catalog = components["schemas"]["TemplateList"];

/**
 * A live session, as the night panel on Home (approved reference `AppHome`): the volt dot and "Running now", the GPU,
 * what it's doing, then the time at 54 px and the cost so far in volt. The whole tile opens the session. Before it
 * runs there is no time or cost yet, and it says nothing is charged. On a phone it is the two-by-two tile at the top of
 * the grid; from 768 px (`wide`) it sits in a row of panels.
 */
export function RunningTile({
  session,
  catalog,
  wide = false,
}: {
  session: Session;
  catalog: Catalog;
  wide?: boolean;
}) {
  const running = session.state === "RUNNING";
  const doing = serviceOf(catalog, session.templateId)?.runningLabel ?? "Running";
  const gpu = session.gpu ? gpuName(session.gpu.model) : null;
  return (
    <TileLink
      href={`/session/?id=${session.id}`}
      tone="night"
      size={wide ? "lg" : "md"}
      className={cn(wide ? "gap-4" : "col-span-2 row-span-2")}
    >
      <span className="flex items-center justify-between gap-3">
        <TileLabel
          tone="night"
          className="flex items-center gap-2 font-bold tracking-wider text-volt uppercase"
        >
          <span
            aria-hidden="true"
            className={cn("size-2 rounded-full bg-running-dot", running && "motion-safe:animate-pulse")}
          />
          {running ? "Running now" : headline(session, catalog)}
        </TileLabel>
        {wide && gpu && <TileLabel tone="night">{gpu}</TileLabel>}
      </span>
      <span className="flex flex-col gap-1">
        <span
          className={cn(
            "text-on-night",
            wide ? "font-title text-display-card" : "text-small text-on-night-2",
          )}
        >
          {doing}
          {!wide && gpu ? ` on ${gpu}` : ""}
        </span>
        {session.startedAt ? (
          <span className="flex flex-wrap items-baseline gap-x-3.5">
            <span className="font-title text-timer">
              <span className="sr-only">Running for </span>
              <Elapsed from={session.startedAt} />
            </span>
            <span className="text-section font-semibold text-volt tabular">
              <Money paise={costSoFar(session)} />
              <span className="sr-only"> so far</span>
            </span>
          </span>
        ) : (
          <span className="text-option font-semibold">Nothing is charged until it runs</span>
        )}
      </span>
    </TileLink>
  );
}
