import { useLocation } from "wouter";
import { useHost } from "@/api/hosting";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { useTitle } from "@/lib/title";
import { HostFlow } from "./HostFlow";
import { PriceForm } from "./PriceForm";
import { useHostId } from "./useHostId";

/** A machine's price and limits (docs/design/hosting/price-phone.html): the form, and Save back to the machine. */
export function Price() {
  const id = useHostId();
  const host = useHost(id);
  const [, navigate] = useLocation();
  useTitle("Price and limits");
  const back = id ? `/hosting/machine/?id=${id}` : "/hosting/";

  return (
    <HostFlow back={back} backLabel={host.data?.name ?? "Hosting"}>
      {id === null ? (
        <EmptyState title="No machine chosen">Open a machine from the Hosting tab.</EmptyState>
      ) : host.data ? (
        <>
          <h1 className="text-title">Price and limits</h1>
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
        <div role="status" aria-label="Loading this machine" className="flex flex-col gap-6">
          <SkeletonLine size="title" width="w-2/3" />
          <Skeleton className="h-14 w-full" />
        </div>
      )}
    </HostFlow>
  );
}
