import { Link } from "wouter";
import { useHosts } from "@/api/hosting";
import { Page } from "@/components/shell/AppShell";
import { FIT_FRAME } from "@/components/shell/Layout";
import { buttonClasses } from "@/components/ui/Button";
import { PageHeading } from "@/components/ui/Heading";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { HOST_PATHS } from "@/sites/host/paths";
import { AddLink, Pitch } from "./Hosting";
import { LaptopPitch } from "./laptop/LaptopHosting";
import { MachineTile } from "./laptop/MachineTile";
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
          <PageHeading>Laptops</PageHeading>
          <Link href={HOST_PATHS.add} className={buttonClasses("primary", false, true)}>
            Add a laptop
          </Link>
        </div>
        {since !== null && <StaleNotice updatedAt={since} />}
        {failed || (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {machines
              ? machines.map((host) => (
                  <MachineTile key={host.id} host={host} className="min-h-service-card" />
                ))
              : [0, 1, 2].map((key) => <Skeleton key={key} tile className="h-service-card" />)}
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
