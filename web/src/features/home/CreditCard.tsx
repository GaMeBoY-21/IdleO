import type { UseQueryResult } from "@tanstack/react-query";
import type { Wallet } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { formatTime } from "@/lib/time";

/** Figures updated before this were restored from an earlier visit (src/api/persisted-cache.ts). */
const PAGE_OPENED_AT = Date.now();

/**
 * The renter's credit, big: what they can spend now, and under it what's on hold and both together, each on a grey
 * fill. A stale refresh is said once, at the top of the screen (staleSince), not here.
 */
export function CreditCard({ wallet }: { wallet: UseQueryResult<Wallet> }) {
  // Figures kept from an earlier visit say when they're from until the fresh ones arrive.
  const restored =
    wallet.data && wallet.isFetching && !wallet.isRefetchError && wallet.dataUpdatedAt < PAGE_OPENED_AT;
  return (
    <section aria-labelledby="credit-title" className="flex flex-col gap-4">
      <h2 id="credit-title" className="sr-only">
        Your credit
      </h2>
      {wallet.data ? (
        <>
          <div className="flex flex-col gap-1">
            <p className="text-small font-semibold text-text-muted">Available</p>
            <Money paise={wallet.data.availablePaise} className="block text-timer" />
            <p className="min-h-4.5 text-caption text-text-muted" role="status">
              {restored ? (
                <>
                  Updated <span className="tabular">{formatTime(wallet.dataUpdatedAt)}</span> · refreshing
                </>
              ) : (
                "What you can spend now."
              )}
            </p>
          </div>
          <dl className="grid grid-cols-2 gap-2">
            <Figure
              label="On hold"
              paise={wallet.data.reservedPaise}
              note="Set aside while a session runs."
            />
            <Figure label="Total" paise={wallet.data.balancePaise} note="Available plus on hold." />
          </dl>
        </>
      ) : wallet.isError ? (
        <ErrorState
          error={wallet.error}
          what="your credit"
          onRetry={() => void wallet.refetch()}
          retrying={wallet.isFetching}
        />
      ) : (
        <CreditSkeleton />
      )}
    </section>
  );
}

function Figure({ label, paise, note }: { label: string; paise: number; note: string }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-control bg-fill px-4 py-3.5">
      <dt className="text-small text-text-muted">{label}</dt>
      <dd className="flex flex-col gap-0.5">
        <Money paise={paise} className="block text-option font-semibold" />
        <span className="min-h-9 text-caption text-text-muted">{note}</span>
      </dd>
    </div>
  );
}

/** Exactly the loaded figures' shape, line for line, so nothing below them moves when they arrive. */
function CreditSkeleton() {
  return (
    <div className="flex flex-col gap-4" role="status" aria-label="Loading your credit">
      <div className="flex flex-col gap-1">
        <SkeletonLine size="small" width="w-1/4" />
        <SkeletonLine size="timer" width="w-1/2" />
        <SkeletonLine size="caption" width="w-1/3" />
      </div>
      <div className="grid grid-cols-2 gap-2">
        {[0, 1].map((key) => (
          <div key={key} className="flex flex-col gap-0.5 rounded-control bg-fill px-4 py-3.5">
            <SkeletonLine size="small" width="w-1/2" />
            <SkeletonLine size="option" width="w-2/3" />
            <div className="min-h-9">
              <SkeletonLine size="caption" width="w-full" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
