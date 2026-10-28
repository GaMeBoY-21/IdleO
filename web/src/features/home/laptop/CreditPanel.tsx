import { useHistory, type Wallet } from "@/api/queries";
import type { UseQueryResult } from "@tanstack/react-query";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";

/**
 * Credit on a laptop's Home, the ion-deep tile (approved reference `AppHome`): what's available at 52 px, what's on hold
 * and the last charge, all the server's. The whole tile opens Credit, where "Add credit" is. The history is asked for
 * once the screen has painted (`enabled`), so it never counts against the first paint.
 */
export function CreditPanel({ wallet, enabled }: { wallet: UseQueryResult<Wallet>; enabled: boolean }) {
  const history = useHistory(enabled);
  const last = history.data?.pages[0]?.entries.find((entry) => entry.direction === "debit");
  return (
    <TileLink href="/credit/" tone="ion-deep" size="lg" className="gap-4">
      <span className="flex items-center justify-between gap-3">
        <TileLabel tone="ion-deep" className="font-bold tracking-wider uppercase">
          Credit
        </TileLabel>
        <span className="rounded-icon bg-on-ion-deep px-4 py-2.5 text-small font-bold text-ink">
          Add credit
        </span>
      </span>
      <span className="flex min-h-14 items-center font-title text-display-figure tabular">
        {wallet.data ? (
          <>
            <span className="sr-only">Available: </span>
            <Money paise={wallet.data.availablePaise} />
          </>
        ) : wallet.isError ? (
          <span className="font-sans text-small">Couldn&apos;t load</span>
        ) : (
          <Skeleton className="h-12 w-48" />
        )}
      </span>
      <span className="min-h-6 text-small tabular">
        {wallet.data && (
          <>
            <Money paise={wallet.data.reservedPaise} /> on hold
            {last && (
              <>
                {" · last charge "}
                <Money paise={last.amountPaise} />
                {", "}
                <RelativeTime value={last.createdAt} />
              </>
            )}
          </>
        )}
      </span>
    </TileLink>
  );
}
