import type { UseQueryResult } from "@tanstack/react-query";
import type { Wallet } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { formatDay, formatTime } from "@/lib/time";

/** Figures updated before this were restored from an earlier visit (src/api/persisted-cache.ts). */
const PAGE_OPENED_AT = Date.now();

/**
 * The renter's credit, as the big dark tile (web/DESIGN.md "Credit"): what they can spend now at 54 px, and under it
 * what's on hold and both together, then how much of it was bought and how much is free (ADR 0039). A stale refresh is said once, at the top of the screen (staleSince), not here.
 */
export function CreditCard({ wallet, wide = false }: { wallet: UseQueryResult<Wallet>; wide?: boolean }) {
  // Figures kept from an earlier visit say when they're from until the fresh ones arrive.
  const restored =
    wallet.data && wallet.isFetching && !wallet.isRefetchError && wallet.dataUpdatedAt < PAGE_OPENED_AT;
  return (
    <Tile
      tone="night"
      size={wide ? "lg" : "md"}
      aria-labelledby="credit-title"
      className="flex flex-col gap-5"
    >
      <h2 id="credit-title" className="sr-only">
        Your credit
      </h2>
      {wallet.data ? (
        <>
          <div className="flex flex-col gap-1">
            <p className="text-small font-semibold text-on-night-2">Available</p>
            <Money paise={wallet.data.availablePaise} className="block text-timer xl:text-timer-lg" />
            <p className="min-h-4.5 text-caption text-on-night-2" role="status">
              {restored ? (
                <>
                  Updated <span className="tabular">{formatTime(wallet.dataUpdatedAt)}</span> · refreshing
                </>
              ) : (
                "What you can spend now."
              )}
            </p>
          </div>
          <dl className="grid grid-cols-2 gap-2.5">
            <Figure
              label="On hold"
              paise={wallet.data.reservedPaise}
              note="Set aside while a session runs."
            />
            <Figure label="Total" paise={wallet.data.balancePaise} note="Available plus on hold." />
            <Figure label="Bought" paise={wallet.data.boughtPaise} note="Spent after free credit." />
            <Figure label="Free" paise={wallet.data.freePaise} note={freeNote(wallet.data)} />
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
    </Tile>
  );
}

/** When the free credit runs out: the soonest expiry, or that it doesn't. */
function freeNote(wallet: Wallet): string {
  if (wallet.freePaise === 0) {
    return "Spent first.";
  }
  return wallet.freeExpiresAt ? `Some expires ${formatDay(wallet.freeExpiresAt)}.` : "Never expires.";
}

function Figure({ label, paise, note }: { label: string; paise: number; note: string }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-control bg-on-night/10 px-4 py-3.5">
      <dt className="text-small text-on-night-2">{label}</dt>
      <dd className="flex flex-col gap-0.5">
        <Money paise={paise} className="block text-option font-semibold" />
        <span className="min-h-9 text-caption text-on-night-2">{note}</span>
      </dd>
    </div>
  );
}

/** Exactly the loaded figures' shape, line for line, so nothing below them moves when they arrive. */
function CreditSkeleton() {
  return (
    <div className="flex flex-col gap-5" role="status" aria-label="Loading your credit">
      <div className="flex flex-col gap-1">
        <SkeletonLine size="small" width="w-1/4" onDark />
        {/* The big figure's line: text-timer, and text-timer-lg from 1200 px, as the figure itself. */}
        <div aria-hidden="true" className="flex h-14 items-center xl:h-15">
          <div className="skeleton-dark h-12 w-1/2 rounded-control" />
        </div>
        <SkeletonLine size="caption" width="w-1/3" onDark />
      </div>
      <div className="grid grid-cols-2 gap-2.5">
        {[0, 1, 2, 3].map((key) => (
          <div key={key} className="flex flex-col gap-0.5 rounded-control bg-on-night/10 px-4 py-3.5">
            <SkeletonLine size="small" width="w-1/2" onDark />
            <SkeletonLine size="option" width="w-2/3" onDark />
            <div className="min-h-9">
              <SkeletonLine size="caption" width="w-full" onDark />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
