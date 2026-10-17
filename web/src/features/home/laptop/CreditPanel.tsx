import { useHistory, type Wallet } from "@/api/queries";
import type { UseQueryResult } from "@tanstack/react-query";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";

/**
 * Credit on a laptop's Home (docs/design/bento/laptop/home-laptop.html): what's available at 40 px, what's on hold and
 * the last charge, all the server's. The whole tile opens Credit. The history is asked for once the screen has painted
 * (`enabled`), so it never counts against the first paint.
 */
export function CreditPanel({ wallet, enabled }: { wallet: UseQueryResult<Wallet>; enabled: boolean }) {
  const history = useHistory(enabled);
  const last = history.data?.pages[0]?.entries.find((entry) => entry.direction === "debit");
  return (
    <TileLink href="/credit/" size="lg" className="gap-1.5 xl:p-6">
      <span className="flex items-center justify-between">
        <TileLabel>Credit</TileLabel>
        <span className="text-caption font-semibold">History ›</span>
      </span>
      {wallet.data ? (
        <span className="text-question tabular">
          <span className="sr-only">Available: </span>
          <Money paise={wallet.data.availablePaise} />
        </span>
      ) : wallet.isError ? (
        <span className="text-small text-text-muted">Couldn&apos;t load</span>
      ) : (
        <Skeleton className="h-11 w-40" />
      )}
      <span className="min-h-4.5 text-caption text-text-muted tabular">
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
