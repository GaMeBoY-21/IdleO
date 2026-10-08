import { Link } from "wouter";
import { useWallet } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { useAfterFirstScreen } from "@/lib/after-first-screen";

const AMOUNT = "inline-block min-w-amount text-center font-semibold tabular";

/** The renter's available credit, as a grey pill on every screen (web/DESIGN.md "Navigation"). */
export function CreditChip() {
  // Shown from the cache at once, and fetched once the screen's own content has loaded; its box is kept, so
  // nothing moves when it arrives.
  const wallet = useWallet(false, useAfterFirstScreen());
  return (
    <Link
      href="/credit/"
      aria-label={wallet.data ? undefined : "Credit"}
      className="flex min-h-touch items-center rounded-full bg-fill px-3.5 text-small hover:bg-fill-selected"
    >
      <span className="sr-only">Available credit </span>
      {wallet.data ? (
        <Money paise={wallet.data.availablePaise} className={AMOUNT} />
      ) : (
        <span className={AMOUNT}>
          <Skeleton className="inline-block h-3 w-full" />
        </span>
      )}
    </Link>
  );
}
