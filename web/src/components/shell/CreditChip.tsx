import { AppLink } from "@/components/ui/AppLink";
import { useWallet } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { useAfterFirstScreen } from "@/lib/after-first-screen";

/**
 * The renter's available credit, a white chip in the header from 768 px (web/DESIGN.md "Laptop layout"). Shown from the
 * cache at once and fetched once the screen's own content has loaded; its box is kept, so nothing moves when it arrives.
 */
export function CreditChip() {
  const wallet = useWallet(false, useAfterFirstScreen());
  return (
    <AppLink
      href="/credit/"
      aria-label={wallet.data ? undefined : "Credit"}
      className="hidden min-h-touch min-w-24 items-center justify-center rounded-full bg-panel px-4 text-small font-bold tabular hover:bg-raised md:flex"
    >
      <span className="sr-only">Available credit </span>
      {wallet.data ? <Money paise={wallet.data.availablePaise} /> : <Skeleton className="h-3 w-16" />}
    </AppLink>
  );
}
