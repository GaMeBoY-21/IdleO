import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import type { Session } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { Money } from "@/components/ui/Money";
import { Elapsed } from "@/components/ui/Time";
import { headline } from "@/features/flow/headline";
import { cn } from "@/lib/cn";
import { gpuName } from "@/lib/gpu";
import { costSoFar } from "@/lib/sessions";

type Catalog = components["schemas"]["TemplateList"];

/**
 * A live session, as the black bar at the top of Home (docs/design/redesign/home-phone.html): what it's doing and for
 * how long ("Fine-tuning · 18:42"), then its GPU and its cost so far. The whole bar opens the session.
 */
export function RunningBar({ session, catalog }: { session: Session; catalog: Catalog }) {
  const running = session.state === "RUNNING";
  return (
    <Link
      href={`/session/?id=${session.id}`}
      className="flex items-center gap-3.5 rounded-control bg-primary px-4 py-3.5 text-primary-fg hover:bg-primary-hover"
    >
      <span
        aria-hidden="true"
        className={cn(
          "size-3 shrink-0 rounded-full bg-inverse-running ring-4 ring-inverse-running/25",
          running && "motion-safe:animate-pulse",
        )}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-body font-semibold break-words">
          {headline(session, catalog)}
          {session.startedAt && (
            <>
              {" · "}
              <Elapsed from={session.startedAt} />
            </>
          )}
        </span>
        <span className="text-caption text-inverse-muted tabular">
          {session.gpu ? `${gpuName(session.gpu.model)} · ` : ""}
          {session.startedAt ? (
            <>
              <Money paise={costSoFar(session)} /> so far
            </>
          ) : (
            "Nothing is charged until it runs"
          )}
        </span>
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </Link>
  );
}
