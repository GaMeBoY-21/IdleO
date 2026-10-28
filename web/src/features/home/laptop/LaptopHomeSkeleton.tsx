import { Skeleton } from "@/components/ui/Skeleton";
import { FRAME } from "@/components/shell/Layout";
import { cn } from "@/lib/cn";

/** The laptop's Home: a column of rows under the page padding (32 px above, 48 px below). */
export const HOME = cn(FRAME, "flex flex-col gap-6 pt-4 pb-8 md:pt-8 md:pb-12");

/** Home's shape on a laptop before anything is known: the same rows, bone tiles. */
export function LaptopHomeSkeleton() {
  return (
    <div role="status" aria-label="Loading" className={HOME}>
      <Skeleton className="h-16 w-full" />
      <div className="grid cols-cards gap-4">
        <Skeleton tile className="h-service-card" />
        <Skeleton tile className="h-service-card" />
        <Skeleton tile className="h-service-card" />
      </div>
      <Skeleton tile className="h-credit-row" />
      <Skeleton tile className="h-recent" />
    </div>
  );
}
