import { Link } from "wouter";
import { useWallet } from "@/api/queries";
import { useAfterFirstScreen } from "@/lib/after-first-screen";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";

const AMOUNT = "inline-block min-w-amount text-right font-semibold tabular";

/** The renter's available credit, quietly, on every screen (web/DESIGN.md principle 2). */
export function CreditChip() {
  // Shown from the cache at once, and fetched once the screen's own content has loaded; its box is kept, so
  // nothing moves when it arrives.
  const wallet = useWallet(false, useAfterFirstScreen());
  return (
    <Link
      href="/credit/"
      className="flex min-h-touch items-center gap-2 rounded-full px-3 text-small hover:bg-sunken"
    >
      <span className="text-text-muted">Credit</span>
      {wallet.data ? (
        <Money paise={wallet.data.availablePaise} className={AMOUNT} />
      ) : (
        // The same box as the amount, so its width doesn't change when the figure arrives.
        <span className={AMOUNT}>
          <Skeleton className="inline-block h-3 w-full" />
        </span>
      )}
    </Link>
  );
}
