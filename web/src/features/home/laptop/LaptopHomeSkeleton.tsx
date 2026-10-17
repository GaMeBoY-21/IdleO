import { Skeleton } from "@/components/ui/Skeleton";
import { FIT_FRAME, GRID } from "@/components/shell/Layout";
import { cn } from "@/lib/cn";

/** The laptop's Home grid: two rows that fill the screen under the header, 12 columns (6 on a tablet). */
export const HOME = cn(FIT_FRAME, GRID, "rows-home xl:fit-screen");

/** Home's shape on a laptop before anything is known: the same grid, bone tiles. */
export function LaptopHomeSkeleton() {
  return (
    <div role="status" aria-label="Loading" className={HOME}>
      <Skeleton tile className="md:col-span-6 xl:col-span-8" />
      <div className="flex min-h-0 flex-col gap-4 md:col-span-6 xl:col-span-4 2xl:gap-5">
        <Skeleton tile className="h-32" />
        <Skeleton tile className="flex-1" />
      </div>
      <Skeleton tile className="md:col-span-6 xl:col-span-8" />
      <Skeleton tile className="md:col-span-6 xl:col-span-4" />
    </div>
  );
}
