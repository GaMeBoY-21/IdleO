import { useEarnings, useHosts, type EarningsEntry, type OwnerEarnings } from "@/api/hosting";
import { Page } from "@/components/shell/AppShell";
import { HostFlow } from "@/components/shell/HostFlow";
import { Button } from "@/components/ui/Button";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { cn } from "@/lib/cn";
import { formatMinutes } from "@/lib/hosting";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { useAfterPaint } from "@/lib/after-first-screen";
import { PayoutHistory, PayoutsTile, StatementTile } from "./Payouts";

const PAYOUT_NOTE = "Paid to your UPI ID every Monday, 7 days after each session.";

const ROW = "flex min-h-18 items-center gap-3 py-4";

/**
 * Earnings across every machine (docs/design/hosting/earnings-phone.html): the total, this week and the time rented,
 * then every credit and every lost-time giveback, newest first, a page at a time. All of it is the ledger's. Below
 * them, the payouts (ADR 0040): what's pending and available, the next payday, every payout and the year's statement,
 * asked for after the first paint.
 */
export function Earnings() {
  useTitle("Earnings");
  const hosts = useHosts();
  const live = hosts.data?.hosts.some((host) => host.currentSession != null) ?? false;
  const earnings = useEarnings(live);
  const owner = earnings.data?.pages[0];
  const entries = earnings.data?.pages.flatMap((page) => page.history.entries);
  const since = staleSince(earnings);
  const wide = useWide();
  const painted = useAfterPaint();
  const next = {
    has: earnings.hasNextPage,
    busy: earnings.isFetchingNextPage,
    failed: earnings.isFetchNextPageError,
    load: () => void earnings.fetchNextPage(),
  };

  if (wide) {
    // From 768 px: the totals in the left rail's dark tile, the history beside it (web/DESIGN.md "Laptop layout").
    return (
      <HostFlow
        back="/hosting/"
        backLabel="Hosting"
        summary={owner ? <Totals owner={owner} heading /> : <TotalsSkeleton />}
      >
        <h1 className="sr-only">Earnings</h1>
        {since !== null && <StaleNotice updatedAt={since} />}
        <PayoutsTile enabled={painted} />
        <StatementTile enabled={painted} />
        {owner && entries ? (
          <History entries={entries} next={next} />
        ) : earnings.isError ? (
          <ErrorState
            error={earnings.error}
            what="your earnings"
            onRetry={() => void earnings.refetch()}
            retrying={earnings.isFetching}
          />
        ) : (
          <HistorySkeleton />
        )}
        <PayoutHistory enabled={painted} />
      </HostFlow>
    );
  }

  return (
    <Page title="Earnings">
      {since !== null && <StaleNotice updatedAt={since} />}
      {owner && entries ? (
        <>
          <Totals owner={owner} />
          <PayoutsTile enabled={painted} />
          <StatementTile enabled={painted} />
          <History entries={entries} next={next} />
          <PayoutHistory enabled={painted} />
        </>
      ) : earnings.isError ? (
        <ErrorState
          error={earnings.error}
          what="your earnings"
          onRetry={() => void earnings.refetch()}
          retrying={earnings.isFetching}
        />
      ) : (
        <EarningsSkeleton />
      )}
    </Page>
  );
}

/** The totals, this week and the hours rented, the free-beta note, and each machine when there are several. */
function Totals({ owner, heading = false }: { owner: OwnerEarnings; heading?: boolean }) {
  return (
    <Tile
      tone="dark"
      size={heading ? "lg" : undefined}
      as="section"
      aria-label="Totals"
      className="flex flex-col gap-5 xl:p-7"
    >
      <div className="flex flex-col gap-1">
        <p className="text-small font-semibold text-dark-muted">All machines, in total</p>
        <Money paise={owner.totalPaise} className="block text-timer" />
      </div>
      <dl className="grid grid-cols-2 gap-2">
        <Figure label="This week">
          <Money paise={owner.weekPaise} />
        </Figure>
        <Figure label="Hours rented">
          <span className="tabular">{formatMinutes(owner.minutesRentedThisWeek)}</span>
        </Figure>
      </dl>
      <p className="max-w-prose text-small text-dark-muted">{PAYOUT_NOTE}</p>
      {owner.machines.length > 1 && (
        <ul aria-label="Each machine" className="flex flex-col divide-y divide-dark-fg/15">
          {owner.machines.map((machine) => (
            <li key={machine.hostId} className="flex min-h-touch items-center justify-between gap-3 py-2">
              <span className="text-small font-semibold break-words">
                {machine.name}
                {machine.removed && <span className="font-normal text-dark-muted"> (removed)</span>}
              </span>
              <Money paise={machine.earnedPaise} className="text-small font-semibold" />
            </li>
          ))}
        </ul>
      )}
    </Tile>
  );
}

/** Every credit and giveback, newest first, a page at a time. */
function History({
  entries,
  next,
}: {
  entries: EarningsEntry[];
  next: { has: boolean; busy: boolean; failed: boolean; load: () => void };
}) {
  return (
    <section aria-labelledby="history-title" className="flex flex-col gap-1">
      <SectionTitle id="history-title">History</SectionTitle>
      <div className="flex flex-col gap-4">
        {entries.length === 0 ? (
          <EmptyState title="Nothing earned yet">
            What your machines earn, and any time given back for a lost session, will be listed here.
          </EmptyState>
        ) : (
          <Tile as="div" className="px-4.5 py-0">
            <ul className="flex flex-col divide-y divide-divider">
              {entries.map((entry) => (
                <EntryRow key={entry.id} entry={entry} />
              ))}
            </ul>
          </Tile>
        )}
        {next.has && (
          <Button variant="secondary" className="self-center" busy={next.busy} onClick={next.load}>
            Show older
          </Button>
        )}
        {next.failed && (
          <p className="text-small text-danger" role="alert">
            We couldn&apos;t load older entries. Try again.
          </p>
        )}
      </div>
    </section>
  );
}

/** The rail's dark tile before the figures arrive: the same blocks and heights. */
function TotalsSkeleton() {
  return (
    <Tile
      tone="dark"
      size="lg"
      className="flex flex-col gap-5 xl:p-7"
      role="status"
      aria-label="Loading your earnings"
    >
      <div className="flex flex-col gap-1">
        <SkeletonLine size="small" width="w-1/3" onDark />
        <SkeletonLine size="timer" width="w-1/2" onDark />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="skeleton-dark h-18 rounded-control" />
        <div className="skeleton-dark h-18 rounded-control" />
      </div>
      <p className="max-w-prose text-small text-dark-muted">{PAYOUT_NOTE}</p>
    </Tile>
  );
}

function HistorySkeleton() {
  return (
    <Tile as="div" aria-hidden="true" className="flex flex-col divide-y divide-divider px-4.5 py-0">
      {[0, 1, 2, 3].map((key) => (
        <div key={key} className={ROW}>
          <div className="flex flex-1 flex-col gap-0.5">
            <SkeletonLine size="option" width="w-1/2" />
            <SkeletonLine size="small" width="w-1/3" />
          </div>
          <SkeletonLine size="option" width="w-1/4" />
        </div>
      ))}
    </Tile>
  );
}

/** Both sections' shape, as one block that the content replaces whole, so nothing in it moves. */
function EarningsSkeleton() {
  return (
    <div role="status" aria-label="Loading your earnings" className="flex flex-col gap-7">
      <Tile tone="dark" className="flex flex-col gap-5">
        <div className="flex flex-col gap-1">
          <SkeletonLine size="small" width="w-1/3" onDark />
          <SkeletonLine size="timer" width="w-1/2" onDark />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div className="skeleton-dark h-18 rounded-control" />
          <div className="skeleton-dark h-18 rounded-control" />
        </div>
        <p className="max-w-prose text-small text-dark-muted">{PAYOUT_NOTE}</p>
      </Tile>
      <Tile as="div" className="flex flex-col divide-y divide-divider px-4.5 py-0">
        {[0, 1, 2, 3].map((key) => (
          <div key={key} className={ROW}>
            <div className="flex flex-1 flex-col gap-0.5">
              <SkeletonLine size="option" width="w-1/2" />
              <SkeletonLine size="small" width="w-1/3" />
            </div>
            <SkeletonLine size="option" width="w-1/4" />
          </div>
        ))}
      </Tile>
    </div>
  );
}

function Figure({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-18 flex-col justify-center gap-0.5 rounded-control bg-dark-fg/10 px-4 py-3.5">
      <dt className="text-small text-dark-muted">{label}</dt>
      <dd className="text-option font-semibold">{children}</dd>
    </div>
  );
}

/** One credit or giveback: what it was for and for how long, the machine and when, and the amount. */
function EntryRow({ entry }: { entry: EarningsEntry }) {
  const givenBack = entry.amountPaise < 0;
  return (
    <li className={ROW}>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="text-option font-semibold break-words">
          {entry.label}
          {!givenBack && entry.minutes > 0 && ` · ${formatMinutes(entry.minutes)}`}
        </p>
        <p className="text-small text-text-muted first-letter:uppercase xl:hidden">
          {entry.machineName} · <RelativeTime value={entry.createdAt} />
        </p>
        {entry.kind === "recovered_after_payout" && (
          <p className="text-small text-text-muted">
            Taken from your unpaid earnings, never from your bank account
          </p>
        )}
        {entry.payoutHeld && (
          <p className="text-small font-semibold text-warning">Held from payout while a report is open</p>
        )}
      </div>
      <p className="hidden w-1/4 shrink-0 text-small text-text-muted first-letter:uppercase xl:block">
        {entry.machineName} · <RelativeTime value={entry.createdAt} />
      </p>
      <p
        className={cn(
          "text-option font-semibold tabular xl:w-24 xl:text-right",
          !givenBack && "text-running",
        )}
      >
        <span className="sr-only">{givenBack ? "Taken back" : "Earned"} </span>
        <span aria-hidden="true">{givenBack ? "−" : "+"}</span>
        <Money paise={Math.abs(entry.amountPaise)} />
      </p>
    </li>
  );
}
