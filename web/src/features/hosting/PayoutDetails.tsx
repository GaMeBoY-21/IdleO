import { useMemo, useState } from "react";
import { useLocation } from "wouter";
import { SectionHeader } from "@/components/ui/Headers";
import { ApiError } from "@/api/errors";
import {
  useCheckUpi,
  useConfirmUpi,
  useForfeit,
  usePayoutDetails,
  usePayoutSummary,
  type PayoutDetails as Details,
  type UpiView,
} from "@/api/payouts";
import { signOut } from "@/auth";
import { Page } from "@/components/shell/AppShell";
import { HostFlow } from "@/components/shell/HostFlow";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { useAfterPaint } from "@/lib/after-first-screen";
import { useNow } from "@/lib/clock";
import { useWide } from "@/lib/media";
import { UPI_SHAPE } from "@/lib/payouts";
import { PanTile } from "@/features/pan/PanTile";
import { problemMessage } from "@/lib/problems";
import { formatDateTime } from "@/lib/time";
import { useTitle } from "@/lib/title";
import { HOST_PATHS } from "@/sites/host/paths";

const LEDE = "Where your earnings go: your PAN, for tax, and a UPI ID registered in the same name.";

/**
 * Hosting, Payout details (ADR 0040): the PAN and the name as on it; the UPI ID, checked with RazorpayX ("Check it"),
 * which shows the name the bank has for it so the host recognises it before using it; the 48-hour hold after a change;
 * and, for deleting the account without a UPI ID, giving the earnings up in writing. Changing where payouts go needs a
 * sign-in in the last 10 minutes; the screen says so and offers it.
 */
export function PayoutDetails() {
  useTitle("Payout details");
  const details = usePayoutDetails();
  const since = staleSince(details);
  const wide = useWide();
  const body = details.data ? (
    <Forms details={details.data} />
  ) : details.isError ? (
    <ErrorState
      error={details.error}
      what="your payout details"
      onRetry={() => void details.refetch()}
      retrying={details.isFetching}
    />
  ) : (
    <FormsSkeleton />
  );
  if (wide) {
    return (
      <HostFlow back={HOST_PATHS.earnings} backLabel="Earnings" title="Payout details" lede={LEDE}>
        {since !== null && <StaleNotice updatedAt={since} />}
        {body}
      </HostFlow>
    );
  }
  return (
    <Page title="Payout details">
      <p className="text-small text-ink-subtle">{LEDE}</p>
      {since !== null && <StaleNotice updatedAt={since} />}
      {body}
    </Page>
  );
}

function Forms({ details }: { details: Details }) {
  const painted = useAfterPaint();
  return (
    <>
      <PanTile pan={details.pan} />
      <UpiTile details={details} />
      <GiveUp enabled={painted} details={details} />
    </>
  );
}

function FormsSkeleton() {
  return (
    <div role="status" aria-label="Loading your payout details" className="flex flex-col gap-6">
      {[0, 1].map((key) => (
        <Tile key={key} className="flex min-h-64 flex-col gap-4">
          <SkeletonLine size="card" width="w-1/3" />
          <div className="skeleton h-14 rounded-control" />
          <div className="skeleton h-14 rounded-control" />
        </Tile>
      ))}
    </div>
  );
}

const UPI_STATE: Record<string, string> = {
  active: "Payouts go here",
  suspect: "A payout to it came back from your bank. Check it again to be paid.",
  review: "We're checking the name on it. You'll be paid once it's approved.",
};

function UpiTile({ details }: { details: Details }) {
  const [, navigate] = useLocation();
  const check = useCheckUpi();
  const confirm = useConfirmUpi();
  const [vpa, setVpa] = useState("");
  const [shown, setShown] = useState(false);
  const typed = vpa.trim();
  // The same UPI ID typed again is the same check, so a repeat after a lost answer makes no second penny drop.
  const idempotencyKey = useMemo(() => `${typed}:${crypto.randomUUID()}`, [typed]);
  const panReady = details.pan?.state === "verified";
  const latest = details.latestCheck;
  const needsSignIn =
    confirm.error instanceof ApiError && confirm.error.type === "/problems/fresh-sign-in-required";
  const signInAgain = () =>
    void signOut().then(() => navigate(`/sign-in/?next=${encodeURIComponent(HOST_PATHS.payoutDetails)}`));
  const shapeError = shown && !UPI_SHAPE.test(typed) ? "A UPI ID looks like name@bank." : undefined;
  return (
    <Tile as="section" aria-labelledby="upi-title" className="flex flex-col gap-4">
      <SectionHeader id="upi-title">UPI ID</SectionHeader>
      {details.inUse && <InUse upi={details.inUse} />}
      {details.inReview && (
        <p className="text-small">
          <span className="font-semibold">{details.inReview.vpa}</span>: {UPI_STATE.review}
        </p>
      )}
      {!details.payoutsAvailable && (
        <p className="text-small text-ink">UPI IDs can&apos;t be checked on this server yet.</p>
      )}
      <form
        noValidate
        className="flex flex-col gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          setShown(true);
          if (UPI_SHAPE.test(typed)) {
            check.mutate({ vpa: typed, idempotencyKey });
          }
        }}
      >
        <Field
          label={details.inUse ? "Change your UPI ID" : "Your UPI ID"}
          value={vpa}
          autoComplete="off"
          spellCheck={false}
          maxLength={100}
          disabled={!panReady}
          error={shapeError}
          hint={
            panReady
              ? "We check it with your bank and show you the name it's registered to."
              : "Add your PAN first: a UPI ID is checked against its name."
          }
          onChange={(event) => setVpa(event.target.value)}
        />
        <div className="flex items-center gap-2">
          <Button
            type="submit"
            variant="ghost"
            busy={check.isPending}
            disabled={!panReady}
            why="Your PAN has to be verified first: the UPI ID is checked against the name on it."
          >
            Check it
          </Button>
          <p role="alert" className="text-small text-danger">
            {check.error ? problemMessage(check.error) : ""}
          </p>
        </div>
      </form>
      {latest && latest.state !== "active" && latest.state !== "review" && (
        <LatestCheck
          upi={latest}
          threshold={details.nameMatchThreshold}
          holdHours={details.changeHoldHours}
          changing={details.inUse != null}
          busy={confirm.isPending}
          onUse={() => confirm.mutate(latest.id)}
        />
      )}
      {(confirm.error || needsSignIn) && (
        <div role="alert" className="flex flex-col gap-2">
          <p className="text-small text-danger">{problemMessage(confirm.error)}</p>
          {needsSignIn && (
            <Button variant="ghost" className="self-start" onClick={signInAgain}>
              Sign in again
            </Button>
          )}
        </div>
      )}
    </Tile>
  );
}

export function InUse({ upi }: { upi: UpiView }) {
  const now = useNow("half-minute");
  const held = upi.holdUntil != null && Date.parse(upi.holdUntil) > now;
  return (
    <div className="flex flex-col gap-1">
      <p className="text-card font-semibold break-all">{upi.vpa}</p>
      {upi.registeredName && <p className="text-small text-ink-subtle">Registered to {upi.registeredName}</p>}
      <p className={upi.state === "active" && !held ? "text-small text-live" : "text-small text-ink"}>
        {held
          ? `Your payout UPI ID was changed. No payout goes to it before ${formatDateTime(upi.holdUntil!)}. Not you? Contact us from Help.`
          : (UPI_STATE[upi.state] ?? upi.state)}
      </p>
    </div>
  );
}

export function LatestCheck({
  upi,
  threshold,
  holdHours,
  changing,
  busy,
  onUse,
}: {
  upi: UpiView;
  threshold: number;
  holdHours: number;
  changing: boolean;
  busy: boolean;
  onUse: () => void;
}) {
  if (upi.state === "checking") {
    return (
      <p role="status" className="text-small text-ink-subtle">
        Checking {upi.vpa} with your bank…
      </p>
    );
  }
  if (upi.state !== "checked") {
    return (
      <p role="status" className="text-small text-danger">
        {upi.vpa}: {upi.failure ?? "This check can't be used. Check the UPI ID again."}
      </p>
    );
  }
  const low = upi.nameMatchScore == null || upi.nameMatchScore < threshold;
  return (
    <div role="status" className="flex flex-col gap-2 rounded-control bg-surface-2 p-4">
      <p className="text-small">
        <span className="font-semibold break-all">{upi.vpa}</span> is registered to{" "}
        <span className="font-semibold">{upi.registeredName ?? "a name the bank didn't give"}</span>.
      </p>
      <p className="text-small text-ink-subtle">
        {low
          ? "That doesn't closely match the name on your PAN, so we'll look at it before paying you."
          : "That matches the name on your PAN."}
        {changing && ` Changing it holds your next payout for ${holdHours} hours, and we email you.`}
      </p>
      <Button className="self-start" busy={busy} onClick={onUse}>
        Use this UPI ID
      </Button>
    </div>
  );
}

/**
 * Deleting the account with earnings and no UPI ID to pay them to (ADR 0040): they're given up in writing, with the
 * person's own words. Shown only then.
 */
function GiveUp({ enabled, details }: { enabled: boolean; details: Details }) {
  const summary = usePayoutSummary(enabled);
  const forfeit = useForfeit();
  const [statement, setStatement] = useState("");
  const unpaid = summary.data ? summary.data.pendingPaise + summary.data.availablePaise : 0;
  if (!summary.data || unpaid <= 0 || details.inUse?.state === "active" || forfeit.isSuccess) {
    return forfeit.isSuccess ? (
      <p role="status" className="text-small">
        Done: you gave up <Money paise={forfeit.data.amountPaise} />. Your account can be deleted now.
      </p>
    ) : null;
  }
  return (
    <Tile as="section" aria-labelledby="give-up-title" className="flex flex-col gap-2">
      <SectionHeader id="give-up-title">Deleting your account without a UPI ID?</SectionHeader>
      <p className="text-small text-ink-subtle">
        Your machines have earned <Money paise={unpaid} /> that hasn&apos;t been paid. Add a UPI ID above and
        it&apos;s paid before the account goes, or give it up in your own words here. Giving it up can&apos;t
        be undone.
      </p>
      <Field
        label="In your words"
        value={statement}
        maxLength={500}
        hint="For example: I give up my unpaid earnings to delete my account."
        onChange={(event) => setStatement(event.target.value)}
      />
      <div className="flex items-center gap-2">
        <Button
          variant="danger"
          solid
          disabled={statement.trim().length < 10}
          why="Write a sentence saying you give them up, at least 10 characters."
          busy={forfeit.isPending}
          onClick={() => forfeit.mutate(statement.trim())}
        >
          Give up my earnings
        </Button>
        <p role="alert" className="text-small text-danger">
          {forfeit.error ? problemMessage(forfeit.error) : ""}
        </p>
      </div>
    </Tile>
  );
}
