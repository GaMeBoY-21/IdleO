import { useHost } from "@/api/hosting";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { ApiError } from "@/api/errors";
import { useNow } from "@/lib/clock";
import { appLine, machineState, rupeesAnHour } from "@/lib/hosting";
import { formatTime } from "@/lib/time";
import { useTitle } from "@/lib/title";
import { lazy, Suspense } from "react";
import { HostFlow } from "@/components/shell/HostFlow";
import { useAfterPaint } from "@/lib/after-first-screen";
import { MachineChip } from "./MachineChip";
import { GpuLine, PrivacyLine, RatingLine, ReliabilityLine, RightNow } from "./MachineFigures";
import { MachineLink } from "./MachineLink";
import { ManageMachine } from "./ManageMachine";
import { PauseControl } from "./PauseControl";
import { useHostId } from "@/lib/use-host-id";

// Run self-test is the last thing on the page and not needed for its first paint: its code and its request wait for the
// paint, like the Credit page's forms, so they never compete with what the paint waits for (ADR 0018).
const SelfTestPanel = lazy(() =>
  import("./SelfTestPanel").then((module) => ({ default: module.SelfTestPanel })),
);

/** Figures updated before this were restored from an earlier visit (src/api/persisted-cache.ts). */
const PAGE_OPENED_AT = Date.now();

/**
 * One machine (designed for the phone first): in the dark tile its state, name and rating, then its GPU with
 * temperature and use against the limit, what it's running now and what that has earned so far, the privacy line, rows
 * to its price and limits, the sessions it ran, and renaming or removing it; and, at the foot, Pause hosting or Resume,
 * then Run self-test, last so what it shows as it loads and runs moves nothing. The dark tile also says how reliable the
 * machine has been.
 */
export function Machine() {
  const id = useHostId();
  const host = useHost(id);
  const now = useNow("half-minute");
  const painted = useAfterPaint();
  useTitle(host.data?.name ?? "Machine");
  const since = staleSince(host);

  if (id === null) {
    return (
      <HostFlow back="/hosting/" backLabel="Hosting">
        <EmptyState title="No machine chosen">Open a machine from the Hosting tab.</EmptyState>
      </HostFlow>
    );
  }
  if (!host.data) {
    const gone =
      host.isError &&
      host.error instanceof ApiError &&
      (host.error.status === 404 || host.error.status === 410);
    return (
      <HostFlow back="/hosting/" backLabel="Hosting">
        {host.isError ? (
          gone ? (
            <EmptyState title="This machine isn't here">
              It may have been removed. Your other machines are on the Hosting tab.
            </EmptyState>
          ) : (
            <ErrorState
              error={host.error}
              what="this machine"
              onRetry={() => void host.refetch()}
              retrying={host.isFetching}
            />
          )
        ) : (
          <MachineSkeleton />
        )}
      </HostFlow>
    );
  }

  const machine = host.data;
  const restored = host.isFetching && !host.isRefetchError && host.dataUpdatedAt < PAGE_OPENED_AT;
  const state = machineState(machine, now);
  const listing = machine.listing;
  return (
    <HostFlow
      back="/hosting/"
      backLabel="Hosting"
      className="xl:grid xl:grid-cols-2 xl:items-start"
      summary={
        <Tile tone="night" size="lg" className="flex flex-col gap-2 xl:p-7">
          <MachineChip state={state} />
          <h1 className="text-title break-words xl:text-question">{machine.name}</h1>
          <p className="min-h-5 text-small text-on-night-2">
            {restored ? (
              <>
                Updated <span className="tabular">{formatTime(host.dataUpdatedAt)}</span> · refreshing
              </>
            ) : machine.online && machine.onlineSince ? (
              <>
                Online since <RelativeTime value={machine.onlineSince} /> · {appLine(machine)}
              </>
            ) : (
              "The host app on it isn't connected."
            )}
          </p>
          <RatingLine host={machine} />
          <ReliabilityLine host={machine} />
        </Tile>
      }
    >
      {since !== null && <StaleNotice updatedAt={since} />}
      <GpuLine host={machine} />
      <RightNow host={machine} />
      <PrivacyLine host={machine} />
      <div className="flex flex-col gap-2.5 xl:col-span-2">
        <MachineLink
          href={`/hosting/price/?id=${machine.id}`}
          title="Price and limits"
          detail={
            listing
              ? `${rupeesAnHour(listing.batchPricePaisePerMin)} an hour · stops above ${listing.gpuTempCapC}°C`
              : "Not set yet"
          }
        />
        <MachineLink
          href={`/hosting/sessions/?id=${machine.id}`}
          title="Sessions it ran"
          detail="What ran here, and what each earned"
        />
        <ManageMachine key={machine.id} host={machine} />
      </div>
      <div className="mt-auto xl:col-span-2">
        <PauseControl host={machine} />
      </div>
      {/* Last, so that its answer arriving, and the list of checks a run shows, move nothing below it (layout shift 0). */}
      {painted && (
        <div className="xl:col-span-2">
          <Suspense fallback={null}>
            <SelfTestPanel host={machine} />
          </Suspense>
        </div>
      )}
    </HostFlow>
  );
}

/** The machine's frame before it arrives: the same blocks, the same heights. */
function MachineSkeleton() {
  return (
    <div role="status" aria-label="Loading this machine" className="flex flex-col gap-3.5">
      <Tile tone="night" className="flex flex-col gap-2">
        <Skeleton className="h-9 w-1/3 rounded-full" />
        <SkeletonLine size="title" width="w-2/3" onDark />
        <SkeletonLine size="small" width="w-1/2" onDark />
        <SkeletonLine size="small" width="w-1/3" onDark />
      </Tile>
      <Tile className="flex min-h-18 flex-col gap-1">
        <SkeletonLine size="section" width="w-1/2" />
        <SkeletonLine size="small" width="w-2/3" />
      </Tile>
      <Skeleton tile className="h-32 w-full" />
      <Skeleton tile className="h-18 w-full" />
      <Skeleton tile className="h-18 w-full" />
      <Skeleton tile className="h-18 w-full" />
    </div>
  );
}
