import type { Wallet } from "@/api/queries";
import { Card, CardTitle } from "@/components/ui/Card";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice } from "@/components/ui/States";
import type { UseQueryResult } from "@tanstack/react-query";
import { formatTime } from "@/lib/time";

/** Figures updated before this were restored from an earlier visit (src/api/persisted-cache.ts). */
const PAGE_OPENED_AT = Date.now();

/** The renter's credit: what they can spend, what's held, and both together, each explained in a line. */
export function CreditCard({ wallet }: { wallet: UseQueryResult<Wallet> }) {
  return (
    <Card aria-labelledby="credit-title">
      <CardTitle
        id="credit-title"
        action={
          // Figures kept from an earlier visit say when they're from until the fresh ones arrive.
          wallet.data &&
          wallet.isFetching &&
          !wallet.isRefetchError &&
          wallet.dataUpdatedAt < PAGE_OPENED_AT ? (
            <p className="text-caption text-text-muted" role="status">
              Updated <span className="tabular">{formatTime(wallet.dataUpdatedAt)}</span> · refreshing
            </p>
          ) : undefined
        }
      >
        Credit
      </CardTitle>
      {wallet.data ? (
        <div className="flex flex-col gap-4">
          {wallet.isRefetchError && <StaleNotice updatedAt={wallet.dataUpdatedAt} />}
          <div>
            <p className="text-small text-text-muted">Available</p>
            <Money
              paise={wallet.data.availablePaise}
              className="block text-heading font-semibold md:text-display"
            />
            <p className="text-caption text-text-muted">What you can spend now.</p>
          </div>
          <dl className="grid grid-cols-2 gap-4 border-t border-border pt-4">
            <div>
              <dt className="text-small text-text-muted">On hold</dt>
              <dd>
                <Money paise={wallet.data.reservedPaise} className="block text-body font-semibold" />
                <p className="min-h-8 text-caption text-text-muted">Set aside while a session runs.</p>
              </dd>
            </div>
            <div>
              <dt className="text-small text-text-muted">Total</dt>
              <dd>
                <Money paise={wallet.data.balancePaise} className="block text-body font-semibold" />
                <p className="min-h-8 text-caption text-text-muted">Available plus on hold.</p>
              </dd>
            </div>
          </dl>
        </div>
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
    </Card>
  );
}

/** Exactly the loaded card's shape, line for line, so nothing below it moves when the figures arrive. */
function CreditSkeleton() {
  return (
    <div className="flex flex-col gap-4" role="status" aria-label="Loading your credit">
      <div>
        <SkeletonLine size="small" width="w-1/4" />
        <SkeletonLine size="heading" md="display" width="w-1/2" />
        <SkeletonLine size="caption" width="w-1/3" />
      </div>
      <div className="grid grid-cols-2 gap-4 border-t border-border pt-4">
        {[0, 1].map((key) => (
          <div key={key}>
            <SkeletonLine size="small" width="w-1/2" />
            <SkeletonLine size="body" width="w-2/3" />
            <div className="min-h-8">
              <SkeletonLine size="caption" width="w-full" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
