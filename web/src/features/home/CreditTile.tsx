import type { UseQueryResult } from "@tanstack/react-query";
import type { Wallet } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

/**
 * The renter's credit, as a white tile (docs/design/bento/home-phone.html): what they can spend now, from the server
 * and never counted on the client. It opens Credit. Without a book-again tile beside it, it is wide.
 */
export function CreditTile({ wallet, wide }: { wallet: UseQueryResult<Wallet>; wide: boolean }) {
  return (
    <TileLink href="/credit/" className={cn(wide && "col-span-2")}>
      <TileLabel>Credit</TileLabel>
      {wallet.data ? (
        <span className="text-figure tabular">
          <span className="sr-only">Available: </span>
          <Money paise={wallet.data.availablePaise} />
        </span>
      ) : wallet.isError ? (
        <span className="text-small text-text-muted">Couldn&apos;t load</span>
      ) : (
        <Skeleton className="h-7 w-24" />
      )}
    </TileLink>
  );
}
