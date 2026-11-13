import { Link } from "wouter";
import { buttonClasses } from "@/components/ui/Button";
import { PageTitle } from "@/components/ui/Headers";
import { Skeleton } from "@/components/ui/Skeleton";
import { FRAME } from "@/components/shell/Layout";
import { cn } from "@/lib/cn";

/** The laptop's Home: a column of rows under the page padding (32 px above, 48 px below). */
export const HOME = cn(FRAME, "flex flex-col gap-6 pt-4 pb-8 md:pt-8 md:pb-12");

/** Home's shape on a laptop before anything is known: the same rows, bone tiles. */
export function LaptopHomeSkeleton() {
  return (
    <div className={HOME}>
      <HomeHeader />
      <div role="status" aria-label="Loading" className="flex flex-col gap-6">
        <div className="grid cols-cards gap-4">
          <Skeleton tile className="h-service-card" />
          <Skeleton tile className="h-service-card" />
          <Skeleton tile className="h-service-card" />
        </div>
        <Skeleton tile className="h-credit-row xl:h-home-panel" />
        <Skeleton tile className="h-recent" />
      </div>
    </div>
  );
}

/** Home's question and its way to book: plain words, so the loading screen draws them too and the page has its title at once. */
export function HomeHeader() {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="flex flex-col gap-1">
        <span className="text-body text-ink-tertiary">Or drop a folder anywhere on this page</span>
        <PageTitle id="question" tabTitle="Home">
          What do you want to run?
        </PageTitle>
      </div>
      <Link href="/book/" className={buttonClasses("primary", false, true)}>
        Book a GPU
      </Link>
    </div>
  );
}
