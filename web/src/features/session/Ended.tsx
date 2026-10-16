import type { ReactNode } from "react";
import { Link } from "wouter";
import type { Session } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { buttonClasses } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { StateChip } from "@/components/ui/StateChip";
import { Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { ending } from "@/lib/labels";
import { bookFrom } from "@/lib/sessions";
import { useAfterPaint } from "@/lib/after-first-screen";
import { formatDuration } from "@/lib/time";
import { ActionFoot } from "./Actions";
import { LogsLater } from "./LogsLater";
import { DownloadOutputs, Outputs } from "./Outputs";
import { Rating } from "./Rating";
import { ReportRow } from "./ReportRow";

/** A session is rated once it has run for a minute (the control plane's rule, ADR 0028). */
const RATEABLE_AFTER_MS = 60_000;

const HEADLINES: Record<string, string> = {
  FAILED: "It stopped with an error",
  CANCELLED: "Cancelled",
  NO_HOST_FREE: "No GPU was free",
  ENDED_REFUNDED: "Ended and refunded",
};

/**
 * A session that has ended. Completed: "Done", the final charge in the dark tile and how long it ran, its outputs in the
 * lime tile with "Download outputs" as the one primary action, the rating, "Report a problem" and "Run again". Failed,
 * cancelled or never matched: why in plain words, the charge (often nothing), and "Try again", which opens Book filled
 * from it. The charge is "so far" until the API says it's settled. On a laptop the session's progress and logs are
 * beside it.
 */
export function Ended({
  session,
  templateName,
  laptop,
  progress,
}: {
  session: Session;
  templateName: string;
  laptop: boolean;
  /** The route of the session's progress, in a tile of its own; shown on a laptop. */
  progress: ReactNode;
}) {
  const done = session.state === "COMPLETED";
  const last = session.events.at(-1)?.reason;
  const stoppedFor = session.events.find((event) => event.state === "FINISHING")?.reason;
  const why = done
    ? stoppedFor === "credit_exhausted" || stoppedFor === "max_spend_reached"
      ? ending(stoppedFor)
      : null
    : ending(session.state === "CANCELLED" ? last : (session.errorCode ?? last));
  const ran =
    session.startedAt && session.endedAt ? `ran ${formatDuration(session.startedAt, session.endedAt)}` : null;
  // Ratings and reports ask the control plane, which isn't needed to paint the session: after its first paint.
  const painted = useAfterPaint();
  const { startedAt, endedAt } = session;
  const canRate =
    done &&
    startedAt != null &&
    endedAt != null &&
    Date.parse(endedAt) - Date.parse(startedAt) >= RATEABLE_AFTER_MS;
  // One cancelled before it ran left nothing to report; one that failed to start can still be reported.
  const canReport = startedAt != null || session.state !== "CANCELLED";

  const foot = (
    <ActionFoot laptop={laptop} note={null}>
      {done ? (
        <>
          <DownloadOutputs id={session.id} />
          <Link href={bookFrom(session)} className={buttonClasses("secondary", true, true)}>
            Run again
          </Link>
        </>
      ) : (
        <Link href={bookFrom(session)} className={buttonClasses("primary", true, true)}>
          Try again
        </Link>
      )}
    </ActionFoot>
  );

  const charge = (
    <Tile tone="dark" className="flex flex-col gap-5">
      <div className="flex flex-col gap-2">
        {!done && (
          <span className="self-start">
            <StateChip state={session.state} />
          </span>
        )}
        <h1 className="text-title">{done ? "Done" : (HEADLINES[session.state] ?? "Ended")}</h1>
        <p className="text-small text-dark-muted">
          {templateName}
          {ran && ` · ${ran}`}
          {session.endedAt && (
            <>
              {" · "}
              <RelativeTime value={session.endedAt} prefix="ended" />
            </>
          )}
        </p>
      </div>
      {why && !done && (
        <div className="flex max-w-prose flex-col gap-1">
          <p className="text-option font-semibold">{why.sentence}</p>
          <p className="text-body text-dark-muted">{why.next}</p>
        </div>
      )}
      <Charge session={session} />
    </Tile>
  );
  const banner = why && done && (
    <Banner tone="info">
      {why.sentence} {why.next}
    </Banner>
  );
  const feedback = (canRate || canReport) && (
    <>
      {canRate && <Rating sessionId={session.id} endedAt={endedAt} enabled={painted} />}
      {canReport && <ReportRow sessionId={session.id} enabled={painted} />}
    </>
  );

  if (!laptop) {
    return (
      <>
        {banner}
        {charge}
        {feedback}
        {session.startedAt && <LogsLater id={session.id} settled={session.settled} laptop={false} />}
        {done && <Outputs id={session.id} />}
        {foot}
      </>
    );
  }
  // The outputs come last in their column, so the list arriving moves nothing under it.
  return (
    <div className="grid grid-cols-12 items-stretch gap-3.5">
      <div className="col-span-6 flex flex-col gap-3.5">
        {banner}
        {charge}
        {feedback}
        {foot}
        {done && <Outputs id={session.id} />}
      </div>
      <div className="col-span-6 flex flex-col gap-3.5">
        {progress}
        {session.startedAt && (
          <div className="flex min-h-panel flex-1">
            <LogsLater id={session.id} settled={session.settled} laptop />
          </div>
        )}
      </div>
    </div>
  );
}

/** The final charge, big, or "Nothing was charged"; until it's settled, what it has cost so far. */
function Charge({ session }: { session: Session }) {
  if (!session.settled) {
    return (
      <p className="flex flex-col gap-1">
        <Money paise={session.chargedPaise + session.accruedPaise} className="text-timer" />
        <span className="text-small text-dark-muted">so far · working out the final charge</span>
      </p>
    );
  }
  if (session.chargedPaise === 0) {
    return <p className="text-section">Nothing was charged</p>;
  }
  return (
    <p className="flex flex-col gap-1">
      <Money paise={session.chargedPaise} className="text-timer" />
      <span className="text-small text-dark-muted">Charged</span>
    </p>
  );
}
