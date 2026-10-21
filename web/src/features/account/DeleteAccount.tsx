import { ChevronLeft } from "lucide-react";
import { Link, useLocation } from "wouter";
import { useCancelDeletion, useDeletion, useRequestDeletion, type DeletionStanding } from "@/api/privacy";
import { signOut } from "@/auth";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { useAfterPaint } from "@/lib/after-first-screen";
import { problemMessage } from "@/lib/problems";
import { formatDay } from "@/lib/time";
import { useTitle } from "@/lib/title";

/** What erasure deletes, in the person's words (ADR 0037, docs/reference/personal-data-map.md). */
export const ERASED = [
  "Your profile: your name, your email and how you sign in, here and at the sign-in provider",
  "Your files, your sessions' outputs, checkpoints and logs",
  "Your machines, removed as Remove does, with their names",
  "Your API keys and device sign-ins",
  "Your messages and what you chose to be told",
  "Your words in reports and ratings",
];

/** "8 years" or "365 days": a period the server gave in days. */
export function period(days: number): string {
  if (days >= 730 && days % 365 <= 2) {
    return `${Math.round(days / 365.25)} years`;
  }
  return days === 1 ? "1 day" : `${days} days`;
}

/** What erasure keeps, and for how long, from the periods the server is configured with. */
export function kept(standing: DeletionStanding): string[] {
  return [
    `Money records (your credit's lines, charges and earnings) for ${period(standing.keptFor.moneyRecordsDays)}, under a number that no longer leads to you`,
    `The security log of sign-ins to your account, for ${period(standing.keptFor.securityLogDays)}`,
    "The stars of ratings you gave, without your name",
    `Our backups, which still hold your account for up to ${period(standing.keptFor.backupsDays)}`,
  ];
}

const LINE = "flex min-h-6 items-start gap-2 text-small";

/** The room "We must keep" takes, loading or loaded, at each width: its four lines, wrapped. */
const KEPT_ROOM = "min-h-kept xl:min-h-kept-wide";

/**
 * Account → Delete my account (ADR 0037): what stops it now, what's erased and what we must keep, the free credit given
 * up, and the wait. Asking needs a sign-in in the last 10 minutes; a link in the email confirms it, and the wait can be
 * cancelled here or from the email until the day.
 */
export function DeleteAccount() {
  useTitle("Delete my account");
  const painted = useAfterPaint();
  const standing = useDeletion(painted);
  const since = staleSince(standing);

  return (
    <AccountFrame title="Delete my account" className="xl:grid xl:grid-cols-5 xl:items-start">
      <Link
        href="/account/data/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-muted underline md:hidden"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Your data
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <Tile
        as="section"
        aria-labelledby="what-title"
        className="flex flex-col gap-3 md:p-6 xl:col-span-3 xl:p-7"
      >
        <SectionTitle id="what-title">What happens</SectionTitle>
        <p className="text-option font-semibold">We erase</p>
        <ul className="flex flex-col gap-1.5">
          {ERASED.map((line) => (
            <li key={line} className={LINE}>
              <span aria-hidden="true">·</span>
              {line}
            </li>
          ))}
        </ul>
        <p className="pt-2 text-option font-semibold">We must keep</p>
        {/* Kept for as long as the server's periods say, which arrive with the rest: the list's room is kept for it
            from the start, so nothing below moves when it does. */}
        {standing.data ? (
          <ul className={`flex flex-col gap-1.5 ${KEPT_ROOM}`}>
            {kept(standing.data).map((line) => (
              <li key={line} className={LINE}>
                <span aria-hidden="true">·</span>
                {line}
              </li>
            ))}
          </ul>
        ) : (
          <div className={`flex flex-col gap-1.5 ${KEPT_ROOM}`} role="status" aria-label="Loading">
            {[0, 1, 2, 3].map((key) => (
              <SkeletonLine key={key} size="small" width="w-5/6" />
            ))}
          </div>
        )}
      </Tile>
      <Tile
        as="section"
        aria-labelledby="delete-title"
        className="flex flex-col gap-3 md:p-6 xl:col-span-2 xl:p-7"
      >
        <SectionTitle id="delete-title">Your account</SectionTitle>
        {standing.data ? (
          <Deletion standing={standing.data} />
        ) : standing.isError ? (
          <ErrorState
            error={standing.error}
            what="where your account stands"
            onRetry={() => void standing.refetch()}
            retrying={standing.isFetching}
          />
        ) : (
          <div className="flex flex-col gap-3" role="status" aria-label="Loading where your account stands">
            <SkeletonLine size="small" width="w-full" />
            <SkeletonLine size="small" width="w-2/3" />
            <div className="h-touch" />
          </div>
        )}
      </Tile>
    </AccountFrame>
  );
}

function Deletion({ standing }: { standing: DeletionStanding }) {
  const [, navigate] = useLocation();
  const ask = useRequestDeletion();
  const cancel = useCancelDeletion();
  const request = standing.request;
  const error = ask.isError
    ? problemMessage(ask.error)
    : cancel.isError
      ? problemMessage(cancel.error)
      : undefined;
  const signInAgain = () =>
    void signOut().then(() => navigate(`/sign-in/?next=${encodeURIComponent("/account/delete/")}`));

  if (request?.state === "erasing") {
    return <p className="text-small">Your account is being deleted now.</p>;
  }
  if (request?.state === "scheduled" && request.deleteAfter) {
    return (
      <div className="flex flex-col gap-3">
        <p className="text-option font-semibold">
          Your account will be deleted on {formatDay(request.deleteAfter)}.
        </p>
        <p className="text-small text-text-muted">
          {request.legalOrder
            ? "A legal order requires it."
            : "Changed your mind? Cancel now, or with the link in our email, until then."}
        </p>
        {!request.legalOrder && (
          <Button variant="secondary" busy={cancel.isPending} onClick={() => cancel.mutate()}>
            Don&apos;t delete my account
          </Button>
        )}
        <p role="alert" className="min-h-4.5 text-caption text-danger">
          {error}
        </p>
      </div>
    );
  }
  const waiting = request?.state === "awaiting_confirmation";
  const blocked = standing.blockers.length > 0;
  return (
    <div className="flex flex-col gap-3">
      {waiting ? (
        <p className="text-small">
          Check your email: we sent you a link to confirm. It works for a day. Then we wait{" "}
          {standing.waitingDays} days, and you can cancel any time until then.
        </p>
      ) : (
        <p className="text-small text-text-muted">
          We email you a link to confirm. Then we wait {standing.waitingDays} days before deleting anything,
          and you can cancel any time until then.
        </p>
      )}
      {standing.losses.map((loss) => (
        <p key={loss.code} className="text-small font-semibold text-warning">
          You give up <Money paise={loss.amountPaise} /> of free credit: it can&apos;t be paid out.
        </p>
      ))}
      {blocked && (
        <div className="flex flex-col gap-1.5">
          <p className="text-small font-semibold">First, this has to end:</p>
          <ul className="flex flex-col gap-1.5">
            {standing.blockers.map((blocker) => (
              <li key={`${blocker.code}${blocker.message}`} className={LINE}>
                <span aria-hidden="true">·</span>
                {blocker.message}
              </li>
            ))}
          </ul>
        </div>
      )}
      {standing.freshSignIn ? (
        <Button variant="danger" disabled={blocked} busy={ask.isPending} onClick={() => ask.mutate()}>
          {waiting ? "Send the link again" : "Delete my account"}
        </Button>
      ) : (
        <>
          <p className="text-small text-text-muted">
            For your safety, sign in again first. It takes a moment.
          </p>
          <Button variant="secondary" disabled={blocked} onClick={signInAgain}>
            Sign in again to continue
          </Button>
        </>
      )}
      <p role="alert" className="min-h-4.5 text-caption text-danger">
        {error}
      </p>
    </div>
  );
}
