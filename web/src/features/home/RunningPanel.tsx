import { Link } from "wouter";
import type { Session } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { ProgressBar } from "@/components/ui/Inputs";
import { Money } from "@/components/ui/Money";
import { Elapsed } from "@/components/ui/Time";
import { ProgressRoute } from "@/features/flow/ProgressRoute";
import { costSoFar } from "@/lib/sessions";

/**
 * Home's right panel on a laptop while a session is live: how long it has run, its cost so far against its cap, and
 * its progress as a route, with a way into the session.
 */
export function RunningPanel({
  session,
  title,
  typicalRuntimeSeconds,
}: {
  session: Session;
  title: string;
  typicalRuntimeSeconds: number | null | undefined;
}) {
  const cost = costSoFar(session);
  const percent = Math.min(100, Math.floor((cost * 100) / session.maxSpendPaise));
  return (
    <>
      <div className="flex flex-col gap-1.5">
        <p className="text-small font-semibold text-text-muted">Your session</p>
        <h2 className="text-title break-words">{title}</h2>
      </div>
      {session.startedAt && (
        <div className="flex flex-col gap-3">
          <p className="text-title">
            <span className="sr-only">Running for </span>
            <Elapsed from={session.startedAt} />
          </p>
          <ProgressBar percent={percent} label="Cost so far against your spending cap" />
          <p className="text-small text-text-muted tabular">
            <Money paise={cost} className="font-semibold text-text" /> so far · stops at{" "}
            <Money paise={session.maxSpendPaise} />
          </p>
        </div>
      )}
      <ProgressRoute session={session} typicalRuntimeSeconds={typicalRuntimeSeconds} />
      <Link href={`/session/?id=${session.id}`} className={buttonClasses("secondary", true)}>
        Open the session
      </Link>
    </>
  );
}
