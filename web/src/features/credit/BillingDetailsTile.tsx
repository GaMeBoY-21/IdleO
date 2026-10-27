import type { UseQueryResult } from "@tanstack/react-query";
import { lazy, Suspense } from "react";
import type { BillingDetails } from "@/api/wallet";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";

/**
 * Billing details (ADR 0039): the state the renter is in (which decides how GST is split on their invoices), and, for
 * a business, its GSTIN and name. Saved only when the server accepts them; the server checks a GSTIN's shape and that
 * it belongs to the state.
 */
const DetailsForm = lazy(() =>
  import("./BillingDetailsForm").then((module) => ({ default: module.DetailsForm })),
);

export function BillingDetailsTile({ details }: { details: UseQueryResult<BillingDetails> }) {
  return (
    <Tile as="section" aria-labelledby="billing-details" className="flex flex-col gap-4">
      <h2 id="billing-details" className="text-title font-semibold">
        Billing details
      </h2>
      {details.data ? (
        // Drawn once from what was loaded; a save updates the cache, and the form keeps its own words ("Saved.").
        <Suspense fallback={<DetailsSkeleton />}>
          <DetailsForm details={details.data} />
        </Suspense>
      ) : details.isError ? (
        <ErrorState
          error={details.error}
          what="your billing details"
          onRetry={() => void details.refetch()}
          retrying={details.isFetching}
        />
      ) : (
        <DetailsSkeleton />
      )}
    </Tile>
  );
}

/** The form's exact shape while the details, or its file, are on their way. */
function DetailsSkeleton() {
  return (
    <div className="flex flex-col gap-4" role="status" aria-label="Loading your billing details">
      {[0, 1, 2].map((key) => (
        <div key={key} className="flex flex-col gap-2">
          <SkeletonLine size="small" width="w-1/3" />
          <div className="min-h-14 rounded-control bg-canvas" />
          <div className="min-h-4.5" />
        </div>
      ))}
      <div className="flex min-h-9 flex-col">
        <SkeletonLine size="caption" width="w-full" />
        <SkeletonLine size="caption" width="w-1/2" />
      </div>
      <div className="min-h-touch w-24 rounded-full bg-canvas" />
    </div>
  );
}
