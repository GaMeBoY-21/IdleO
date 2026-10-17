import { ChevronRight, Plus } from "lucide-react";
import { Link } from "wouter";
import { useEarnings, useHosts } from "@/api/hosting";
import { Page } from "@/components/shell/AppShell";
import { buttonClasses } from "@/components/ui/Button";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { Money } from "@/components/ui/Money";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { formatTime } from "@/lib/time";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { LaptopHosting, LaptopPitch } from "./laptop/LaptopHosting";
import { MachineRow } from "./MachineRow";
import { WeekBars, WeekBarsSkeleton } from "./WeekBars";

/** Figures updated before this were restored from an earlier visit (src/api/persisted-cache.ts). */
const PAGE_OPENED_AT = Date.now();

/**
 * The Hosting tab (designed for the phone first): what you earned this week and each of the last 7 days, on the dark tile, your machines with their states, and Add a machine; with none, what hosting is and the one
 * step to start. Payouts aren't built: the line says they start with the paid pilot (ADR 0023).
 */
export function Hosting() {
  useTitle("Hosting");
  const hosts = useHosts();
  const live = hosts.data?.hosts.some((host) => host.currentSession != null) ?? false;
  const earnings = useEarnings(live);
  const owner = earnings.data?.pages[0];
  const since = staleSince(hosts, earnings);
  const machines = hosts.data?.hosts;
  // Figures kept from an earlier visit say when they're from until the fresh ones arrive.
  const restored = earnings.isFetching && !earnings.isRefetchError && earnings.dataUpdatedAt < PAGE_OPENED_AT;

  const wide = useWide();
  if (wide && !hosts.isError) {
    return (
      <>
        {since !== null && (
          <div className="px-6 pb-4 xl:px-8 2xl:px-10">
            <StaleNotice updatedAt={since} />
          </div>
        )}
        {machines?.length === 0 ? (
          <LaptopPitch />
        ) : (
          <LaptopHosting
            machines={machines}
            owner={owner}
            earnings={earnings}
            restoredAt={restored ? earnings.dataUpdatedAt : null}
          />
        )}
      </>
    );
  }

  return (
    <Page title="Hosting">
      {since !== null && <StaleNotice updatedAt={since} />}
      {machines?.length === 0 ? (
        <Pitch />
      ) : (
        <>
          <Tile tone="dark" aria-labelledby="week-title" className="flex flex-col gap-5">
            <h2 id="week-title" className="sr-only">
              Your earnings
            </h2>
            {owner ? (
              <>
                <div className="flex flex-col gap-1.5">
                  <p className="text-small font-semibold text-dark-muted">
                    Earned this week
                    {restored && (
                      <span className="font-normal">
                        {" "}
                        · from <span className="tabular">{formatTime(earnings.dataUpdatedAt)}</span>,
                        refreshing
                      </span>
                    )}
                  </p>
                  <Money paise={owner.weekPaise} className="block text-timer" />
                  <p className="text-small text-dark-muted tabular">
                    <Money paise={owner.totalPaise} /> in total · payouts start with the paid pilot
                  </p>
                </div>
                <WeekBars days={owner.days} />
                <Link
                  href="/hosting/earnings/"
                  className="flex min-h-touch items-center gap-2 self-start text-small font-semibold text-live hover:underline"
                >
                  Earnings, all machines
                  <ChevronRight aria-hidden="true" className="size-4" strokeWidth={2} />
                </Link>
              </>
            ) : earnings.isError ? (
              <ErrorState
                error={earnings.error}
                what="your earnings"
                onRetry={() => void earnings.refetch()}
                retrying={earnings.isFetching}
              />
            ) : (
              <EarnedSkeleton />
            )}
          </Tile>
          <section aria-labelledby="machines-title" className="flex flex-col gap-3">
            <SectionTitle id="machines-title">Your machines</SectionTitle>
            {machines ? (
              <ul className="flex flex-col gap-2">
                {machines.map((host) => (
                  <li key={host.id}>
                    <MachineRow host={host} />
                  </li>
                ))}
              </ul>
            ) : hosts.isError ? (
              <ErrorState
                error={hosts.error}
                what="your machines"
                onRetry={() => void hosts.refetch()}
                retrying={hosts.isFetching}
              />
            ) : (
              <div role="status" aria-label="Loading your machines" className="flex flex-col gap-2">
                <Skeleton tile className="h-18 w-full" />
                <Skeleton tile className="h-18 w-full" />
              </div>
            )}
          </section>
          {machines && <AddLink />}
        </>
      )}
    </Page>
  );
}

function AddLink() {
  return (
    <Link href="/hosting/add/" className={buttonClasses("secondary", true, true)}>
      <Plus aria-hidden="true" className="size-5" strokeWidth={2} />
      Add a machine
    </Link>
  );
}

/** With no machines: what hosting is, and the one step. */
function Pitch() {
  return (
    <Tile aria-labelledby="pitch-title" className="flex flex-col gap-5 p-6">
      <h2 id="pitch-title" className="text-title">
        Earn from the GPU you aren&apos;t using
      </h2>
      <p className="max-w-prose text-body text-text-muted">
        Add a gaming laptop or desktop with an NVIDIA RTX GPU. Renters&apos; jobs run in a locked box, never
        in your files, and you set the price. Pause or take it back whenever you like.
      </p>
      <Link href="/hosting/add/" className={buttonClasses("primary", true, true)}>
        Add a machine
      </Link>
    </Tile>
  );
}

/** What's above the machines before the figures arrive: the same lines, the same bars. */
function EarnedSkeleton() {
  return (
    <div role="status" aria-label="Loading your earnings" className="flex flex-col gap-5">
      <div className="flex flex-col gap-1.5">
        <SkeletonLine size="small" width="w-1/3" onDark />
        <SkeletonLine size="timer" width="w-1/2" onDark />
        <SkeletonLine size="small" width="w-2/3" onDark />
      </div>
      <WeekBarsSkeleton />
      <div className="flex min-h-touch items-center">
        <SkeletonLine size="small" width="w-1/2" onDark />
      </div>
    </div>
  );
}
