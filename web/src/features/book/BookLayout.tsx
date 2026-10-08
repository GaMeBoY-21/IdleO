import { ChevronLeft } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";
import { TwoPanels } from "@/features/flow/TwoPanels";

/**
 * Book's frame. On a laptop, the two panels (docs/design/redesign/book-laptop.html): the choices and the button on
 * the left, what happens next on the right. On a phone (book-phone.html), a flow without the bars: a grey area with
 * a back button and the route's summary, and under it a sheet holding the rest, the button at its foot.
 */
export function BookLayout({
  laptop,
  left,
  right,
  summary,
  back,
}: {
  laptop: boolean;
  left: ReactNode;
  right: ReactNode;
  summary: ReactNode;
  back: string;
}) {
  if (laptop) {
    return <TwoPanels left={left} right={right} rightLabel="What happens next" />;
  }
  return (
    <div className="flex flex-1 flex-col bg-fill">
      <div className="flex flex-col gap-4 px-4 pt-3 pb-5 md:px-6">
        <Link
          href={back}
          aria-label="Back"
          className="flex size-11 items-center justify-center rounded-full bg-ground hover:bg-fill-selected"
        >
          <ChevronLeft aria-hidden="true" className="size-5.5" strokeWidth={2} />
        </Link>
        {summary}
      </div>
      <div className="flex flex-1 flex-col gap-4 rounded-t-sheet bg-ground px-4 pt-3 pb-5 shadow-sheet md:px-6">
        <span aria-hidden="true" className="h-1.5 w-10 self-center rounded-full bg-divider" />
        {left}
      </div>
    </div>
  );
}
