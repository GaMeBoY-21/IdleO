import { Check, Circle, Minus, X } from "lucide-react";
import { useId } from "react";
import { useRunSelfTest, useSelfTest, type Host, type SelfTestRun } from "@/api/hosting";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { SkeletonLine, Skeleton } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { SELF_TEST_CHECKS, selfTestRefusal } from "@/lib/hosting";
import { problemMessage } from "@/lib/problems";

/** One row of the list: the check's words, and what it found once the app has sent it. */
type Row = {
  name: string;
  label: string;
  result: SelfTestRun["checks"][number] | undefined;
};

/** Every check the app runs, each with its result if it has arrived, then any the app sent that we don't know. */
export function selfTestRows(run: SelfTestRun | null | undefined): Row[] {
  const known = new Set(SELF_TEST_CHECKS.map(([name]) => name));
  const found = (name: string) => run?.checks.find((check) => check.name === name);
  return [
    ...SELF_TEST_CHECKS.map(([name, label]) => ({ name, label, result: found(name) })),
    ...(run?.checks ?? [])
      .filter((check) => !known.has(check.name))
      .map((check) => ({ name: check.name, label: check.name, result: check })),
  ];
}

/** What the run's state says, in a sentence. */
export function selfTestSummary(run: SelfTestRun | null | undefined, done: number, total: number) {
  if (!run) {
    return "Not run from here yet.";
  }
  switch (run.state) {
    case "running":
      return `Running · ${done} of ${total} checks done`;
    case "passed":
      return "Passed";
    case "failed":
      return "A check failed";
    case "refused":
      return selfTestRefusal(run.refused);
    default:
      return "The app didn't answer in time. Make sure IdleO is open on the machine, then run it again.";
  }
}

/**
 * "Run self-test" on a machine's page (ADR 0034): asks the app on the machine to check itself now, and shows each check
 * as it arrives. Every check has its row from the start, waiting for its result, so the list never grows under the
 * person's eyes. The button says why when it's off: the machine is offline, running a session, already testing, or has
 * used its six runs of the day.
 */
export function SelfTestPanel({ host }: { host: Host }) {
  const headingId = useId();
  const status = useSelfTest(host.id);
  const start = useRunSelfTest(host.id);
  const since = staleSince(status);
  const body = status.data;
  const run = body?.run ?? null;
  const rows = selfTestRows(run);
  const done = rows.filter((row) => row.result).length;

  return (
    <Tile as="section" aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="text-small font-semibold text-text-2">
        Self-test
      </h2>
      {!body ? (
        status.isError ? (
          <ErrorState
            error={status.error}
            what="this machine's self-test"
            onRetry={() => void status.refetch()}
            retrying={status.isFetching}
          />
        ) : (
          <SelfTestSkeleton />
        )
      ) : (
        <>
          {since !== null && <StaleNotice updatedAt={since} />}
          <p className="text-small text-text-2">
            Checks this machine the way renters rely on it: its Linux, your GPU, the lockdown that keeps jobs
            away from your files, the disk and the speed of its line. It takes a minute or two.
          </p>
          <p className="min-h-5 text-small font-medium" aria-live="polite">
            {selfTestSummary(run, done, SELF_TEST_CHECKS.length)}
            {run && run.state !== "running" && run.finishedAt ? (
              <>
                {" "}
                · <RelativeTime value={run.finishedAt} className="font-normal text-text-2" />
              </>
            ) : null}
          </p>
          {run && (
            <ol className="flex flex-col gap-2" aria-label="Checks">
              {rows.map((row) => (
                <CheckRow key={row.name} row={row} over={run.state !== "running"} />
              ))}
            </ol>
          )}
          {start.error && <Banner tone="danger">{problemMessage(start.error)}</Banner>}
          <Button
            large
            block
            variant="ghost"
            busy={start.isPending}
            disabled={!body.canRun}
            onClick={() => start.mutate()}
          >
            Run self-test
          </Button>
          <p className="min-h-5 text-center text-small text-text-2">
            {body.canRun ? `Up to ${body.perDay} a day.` : (body.whyNot?.message ?? "")}
          </p>
        </>
      )}
    </Tile>
  );
}

/** A check waiting, passed, failed, or (once the run is over) never run. */
function CheckRow({ row, over }: { row: Row; over: boolean }) {
  const { result } = row;
  const state = result ? (result.passed ? "passed" : "failed") : over ? "not run" : "waiting";
  return (
    <li className="flex items-start gap-2.5">
      <span
        aria-hidden="true"
        className={
          state === "failed"
            ? "mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-danger text-danger-fg"
            : state === "passed"
              ? "mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-volt text-ink"
              : "mt-0.5 flex size-5 shrink-0 items-center justify-center text-text-2"
        }
      >
        {state === "passed" ? (
          <Check className="size-3.5" strokeWidth={2.5} />
        ) : state === "failed" ? (
          <X className="size-3.5" strokeWidth={2.5} />
        ) : state === "not run" ? (
          <Minus className="size-4" strokeWidth={1.75} />
        ) : (
          <Circle className="size-3.5 motion-safe:animate-pulse" strokeWidth={1.75} />
        )}
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="text-small">
          {row.label}
          <span className="sr-only"> — {state}</span>
        </span>
        {/* A failure says what it found in full; a pass takes one line, so a result arriving moves nothing. */}
        <span
          className={
            state === "failed"
              ? "min-h-4.5 text-caption break-words text-text-2"
              : "min-h-4.5 truncate text-caption text-text-2"
          }
          title={result?.detail}
        >
          {result?.detail ?? ""}
        </span>
      </span>
    </li>
  );
}

/** The panel before its first answer: the same blocks at the same heights. */
function SelfTestSkeleton() {
  return (
    <div role="status" aria-label="Loading the self-test" className="flex flex-col gap-3">
      <SkeletonLine size="small" width="w-full" />
      <SkeletonLine size="small" width="w-2/3" />
      <Skeleton className="h-14 w-full rounded-full" />
    </div>
  );
}
