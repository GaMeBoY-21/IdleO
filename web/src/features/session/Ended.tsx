import { Link } from "wouter";
import type { Session } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { buttonClasses } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { StateChip } from "@/components/ui/StateChip";
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
 * A session that has ended. Completed: "Done", the final charge and how long it ran, its outputs with "Download
 * outputs" as the one primary action, and "Run again". Failed, cancelled or never matched: why in plain words, the
 * charge (often nothing), and "Try again", which opens Book filled from it. The charge is "so far" until the API
 * says it's settled.
 */
export function Ended({
  session,
  templateName,
  laptop,
}: {
  session: Session;
  templateName: string;
  laptop: boolean;
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
    <ActionFoot laptop={laptop} note={null} atEnd={!laptop}>
      {done ? (
        <>
          <DownloadOutputs id={session.id} />
          <Link href={bookFrom(session)} className={buttonClasses("outline", true, true)}>
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

  return (
    <>
      <div className="flex flex-col gap-3">
        {!done && (
          <span className="self-start">
            <StateChip state={session.state} />
          </span>
        )}
        <h1 className={laptop ? "text-question" : "text-title"}>
          {done ? "Done" : (HEADLINES[session.state] ?? "Ended")}
        </h1>
        <p className="text-small text-text-muted">
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
      {why &&
        (done ? (
          <Banner tone="info">
            {why.sentence} {why.next}
          </Banner>
        ) : (
          <div className="flex max-w-prose flex-col gap-1">
            <p className="text-option font-semibold">{why.sentence}</p>
            <p className="text-body text-text-muted">{why.next}</p>
          </div>
        ))}
      <Charge session={session} />
      {(canRate || canReport) && (
        <div className="flex flex-col gap-5">
          {canRate && <Rating sessionId={session.id} endedAt={endedAt} enabled={painted} />}
          {canReport && <ReportRow sessionId={session.id} enabled={painted} />}
        </div>
      )}
      {/* A laptop's actions come before the outputs, so the list arriving moves nothing; a phone's stay in its bar. */}
      {laptop && foot}
      {!laptop && session.startedAt && <LogsLater id={session.id} settled={session.settled} laptop={false} />}
      {done && <Outputs id={session.id} />}
      {!laptop && foot}
    </>
  );
}

/** The final charge, big, or "Nothing was charged"; until it's settled, what it has cost so far. */
function Charge({ session }: { session: Session }) {
  if (!session.settled) {
    return (
      <p className="flex flex-col gap-1">
        <Money paise={session.chargedPaise + session.accruedPaise} className="text-title" />
        <span className="text-small text-text-muted">so far · working out the final charge</span>
      </p>
    );
  }
  if (session.chargedPaise === 0) {
    return <p className="text-option font-semibold">Nothing was charged</p>;
  }
  return (
    <p className="flex flex-col gap-1">
      <Money paise={session.chargedPaise} className="text-title" />
      <span className="text-small text-text-muted">Charged</span>
    </p>
  );
}
