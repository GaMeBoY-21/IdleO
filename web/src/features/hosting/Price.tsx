import { useLocation } from "wouter";
import { useHost } from "@/api/hosting";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { useTitle } from "@/lib/title";
import { HostFlow } from "@/components/shell/HostFlow";
import { PriceForm } from "./PriceForm";
import { useHostId } from "@/lib/use-host-id";

/** A machine's price and limits (docs/design/hosting/price-phone.html): the form, and Save back to the machine. */
export function Price() {
  const id = useHostId();
  const host = useHost(id);
  const [, navigate] = useLocation();
  useTitle("Price and limits");
  const back = id ? `/hosting/machine/?id=${id}` : "/hosting/";

  return (
    <HostFlow
      back={back}
      backLabel={host.data?.name ?? "Hosting"}
      title="Price and limits"
      lede="What a renter pays an hour on this machine, and when it stops to keep your GPU cool. You can change it any time."
    >
      {id === null ? (
        <EmptyState title="No machine chosen">Open a machine from the Hosting tab.</EmptyState>
      ) : host.data ? (
        <>
          <PriceForm key={host.data.id} host={host.data} onSaved={() => navigate(back)} />
        </>
      ) : host.isError ? (
        <ErrorState
          error={host.error}
          what="this machine"
          onRetry={() => void host.refetch()}
          retrying={host.isFetching}
        />
      ) : (
        <div role="status" aria-label="Loading this machine" className="flex flex-col gap-3.5">
          <Skeleton tile className="h-24 w-full" />
        </div>
      )}
    </HostFlow>
  );
}
