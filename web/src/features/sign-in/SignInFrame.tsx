import { ChevronLeft } from "lucide-react";
import { lazy, Suspense } from "react";
import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { useLaptop } from "@/lib/media";
import { siteLink } from "@/lib/site-links";

/**
 * The signed-out screens, and the pages for a wrong link or a broken screen: the name, and one white tile on the page
 * colour holding one big question and what answers it. On a phone and a tablet a narrow column. From 1200 px the tile
 * is 7 columns of the grid, centred (web/DESIGN.md "Laptop layout"); with `collage`, as on sign-in, it is the left 5
 * with a collage of what the product is, in colour tiles, in the 7 beside it. The collage says only what the public
 * site says, with no numbers.
 */
// The collage is only drawn from 1200 px, so it is a chunk of its own: a phone's first load doesn't carry it.
const Collage = lazy(() => import("./Collage").then((module) => ({ default: module.Collage })));

export function SignInFrame({
  title,
  children,
  collage = false,
  back = false,
}: {
  title: string;
  children: ReactNode;
  collage?: boolean;
  /** A small "← idleo" in place of the wordmark, back to the website's front door (the sign-in screen). */
  back?: boolean;
}) {
  const laptop = useLaptop();
  return (
    <main className="flex min-h-dvh flex-col items-center px-3.5 pt-4 pb-12 md:justify-center md:py-12 xl:items-stretch xl:px-0 xl:py-0">
      <div className="flex w-full max-w-narrow flex-col gap-4 xl:max-w-none xl:flex-1 xl:px-8 xl:py-8 2xl:px-10">
        {back ? (
          <a
            href={siteLink("/")}
            aria-label="Back to the idleo website"
            className="flex min-h-touch items-center gap-1 self-start px-1.5 xl:px-0"
          >
            <ChevronLeft aria-hidden="true" className="size-5" strokeWidth={2} />
            <Wordmark />
          </a>
        ) : (
          <Wordmark className="self-start px-1.5 xl:px-0" />
        )}
        <div
          className={cn(
            "flex flex-col gap-4 xl:flex-1",
            collage
              ? "xl:grid xl:grid-cols-12 xl:items-center xl:gap-4 2xl:gap-5"
              : "xl:items-center xl:justify-center",
          )}
        >
          <Tile
            as="div"
            size="lg"
            className={cn("flex flex-col", collage ? "xl:col-span-5 xl:justify-center" : "xl:w-7/12")}
          >
            <h1 className="mb-3 text-title xl:text-question">{title}</h1>
            {children}
          </Tile>
          {collage && laptop && (
            <Suspense fallback={<div className="xl:col-span-7" />}>
              <Collage />
            </Suspense>
          )}
        </div>
      </div>
    </main>
  );
}
