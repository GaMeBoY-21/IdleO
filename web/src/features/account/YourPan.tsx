import { usePanDetails } from "@/api/pan";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { PanTile } from "@/features/pan/PanTile";
import { useAfterPaint } from "@/lib/after-first-screen";
import { useTitle } from "@/lib/title";

/**
 * Account → Your PAN (ADR 0042): a renter's own images need a PAN on file, so they give it here, in the renter app,
 * rather than on the host site. The same tile as a host's Payout details, and the same record.
 */
export function YourPan() {
  useTitle("Your PAN");
  const painted = useAfterPaint();
  const details = usePanDetails(painted);
  const why =
    "Running your own container images needs your PAN on file. Only its last 4 characters are ever shown.";
  return (
    <AccountFrame title="Your PAN">
      {details.data ? (
        <PanTile pan={details.data.pan} why={why} />
      ) : details.isError ? (
        <ErrorState
          error={details.error}
          what="your PAN"
          onRetry={() => void details.refetch()}
          retrying={details.isFetching}
        />
      ) : (
        <Tile role="status" aria-label="Loading your PAN" className="flex flex-col gap-4">
          <SkeletonLine size="section" width="w-1/4" />
          <SkeletonLine size="small" width="w-3/4" />
          <SkeletonLine size="body" width="w-full" />
          <SkeletonLine size="body" width="w-full" />
        </Tile>
      )}
    </AccountFrame>
  );
}
