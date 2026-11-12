import { Link } from "wouter";
import { useHosts } from "@/api/hosting";
import { PageTitle } from "@/components/ui/Headers";
import { Page } from "@/components/shell/AppShell";
import { FIT_FRAME } from "@/components/shell/Layout";
import { buttonClasses } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { HOST_PATHS } from "@/sites/host/paths";
import { AddLink, Pitch } from "./Hosting";
import { AddTile, LaptopPitch } from "./laptop/LaptopHosting";
import { MachineTile } from "./laptop/MachineTile";
import { useAfterPaint } from "@/lib/after-first-screen";
import { AtAGlance } from "./laptop/AtAGlance";
import { DemandTile, HelpTile, ScoreTile } from "./laptop/HomeCards";
import { MachineRow } from "./MachineRow";

/**
 * The host site's Laptops tab (ADR 0058): every laptop with its state, GPU, price and what it's doing, each opening its
 * own screen, and Add a laptop. With none, what hosting is and the one step to start.
 */
export function Laptops() {
  useTitle("Laptops");
  const hosts = useHosts();
  const machines = hosts.data?.hosts;
  const since = staleSince(hosts);
  const wide = useWide();
  const painted = useAfterPaint();

  if (machines?.length === 0) {
    return wide ? (
      <LaptopPitch />
    ) : (
      <Page title="Laptops">
        <Pitch />
      </Page>
    );
  }
  const failed = hosts.isError && (
    <ErrorState
      error={hosts.error}
      what="your laptops"
      onRetry={() => void hosts.refetch()}
      retrying={hosts.isFetching}
    />
  );
  if (wide) {
    return (
      <div className={cn(FIT_FRAME, "flex flex-col gap-6")}>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <PageTitle>Laptops</PageTitle>
          <Link href={HOST_PATHS.add} className={buttonClasses("ghost", false, true)}>
            Add a laptop
          </Link>
        </div>
        {since !== null && <StaleNotice updatedAt={since} />}
        {failed || (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {machines
              ? machines.map((host) => <MachineTile key={host.id} host={host} className="xl:h-host-panel" />)
              : [0, 1].map((key) => <Skeleton key={key} tile className="h-service-card xl:h-host-panel" />)}
            {/* Adding another is the grid's last tile: what it takes, and the one step. */}
            <AddTile className="xl:h-host-panel" />
          </div>
        )}
        {!hosts.isError && machines && machines.length > 0 && (
          <div className="grid items-start gap-4 xl:grid-cols-3">
            <div className="xl:col-span-2">
              <AtAGlance machines={machines} enabled={painted} />
            </div>
            <HelpTile />
            <DemandTile host={machines[0]} enabled={painted} className="xl:col-span-2 xl:h-host-panel" />
            <ScoreTile host={machines[0]} className="xl:h-host-panel" />
          </div>
        )}
      </div>
    );
  }
  return (
    <Page title="Laptops">
      {since !== null && <StaleNotice updatedAt={since} />}
      {failed ||
        (machines ? (
          <ul className="flex flex-col gap-2">
            {machines.map((host) => (
              <li key={host.id}>
                <MachineRow host={host} />
              </li>
            ))}
          </ul>
        ) : (
          <div role="status" aria-label="Loading your laptops" className="flex flex-col gap-2">
            <Skeleton tile className="h-18 w-full" />
            <Skeleton tile className="h-18 w-full" />
          </div>
        ))}
      {machines && <AddLink />}
    </Page>
  );
}
