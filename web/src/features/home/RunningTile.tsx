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
 * A live session, as the dark tile at the top of Home (docs/design/bento/home-phone.html): what it's doing and on which
 * GPU, then the time at 54 px and the cost so far in lime. The whole tile opens the session. Before it runs there is
 * no time or cost yet, and it says nothing is charged.
 */
export function RunningTile({ session, catalog }: { session: Session; catalog: Catalog }) {
  const running = session.state === "RUNNING";
  const doing = serviceOf(catalog, session.templateId)?.runningLabel ?? "Running";
  return (
    <TileLink href={`/session/?id=${session.id}`} tone="dark" className="col-span-2 row-span-2">
      <TileLabel tone="dark" className="flex items-center gap-2">
        <span
          aria-hidden="true"
          className={cn("size-2 rounded-full bg-live", running && "motion-safe:animate-pulse")}
        />
        {running ? "Running now" : headline(session, catalog)}
      </TileLabel>
      <span className="flex flex-col gap-1">
        <span className="text-small text-dark-muted">
          {doing}
          {session.gpu ? ` on ${gpuName(session.gpu.model)}` : ""}
        </span>
        {session.startedAt ? (
          <span className="flex flex-wrap items-baseline gap-x-3.5">
            <span className="text-timer">
              <span className="sr-only">Running for </span>
              <Elapsed from={session.startedAt} />
            </span>
            <span className="text-section font-semibold text-live tabular">
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
