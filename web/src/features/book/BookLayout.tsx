import type { ReactNode } from "react";
import { FlowHeader } from "@/components/shell/AppShell";
import { FRAME, GRID } from "@/components/shell/Layout";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

/**
 * Book's frame (web/DESIGN.md "Book"). On a laptop, the questions in a column of tiles on the left and, on the right,
 * what happens next in a white tile, or "Your request" (`service`), the dark tile, which sticks to the top as the
 * questions scroll. On a phone, a flow without the bars: the services under a back button, or a service's questions
 * as tiles with "Your request" held at the foot (part of `left`), its own "← All services" at the top.
 */
export function BookLayout({
  laptop,
  left,
  right,
  service = false,
}: {
  laptop: boolean;
  left: ReactNode;
  right: ReactNode;
  service?: boolean;
}) {
  if (laptop) {
    return (
      <div className={cn(FRAME, GRID, "flex-1 items-start pt-4 pb-12 md:pt-8")}>
        <div className="col-span-6 flex min-w-0 flex-col gap-3.5 xl:col-span-7">{left}</div>
        <section
          aria-label={service ? "Your request" : "What happens next"}
          className="sticky top-8 col-span-6 flex flex-col xl:col-span-5"
        >
          {service ? right : right !== null && <Tile className="flex flex-col gap-8 p-6">{right}</Tile>}
        </section>
      </div>
    );
  }
  return (
    <div className="flex flex-1 flex-col">
      {!service && <FlowHeader back="/" />}
      <div
        className={
          service
            ? "flex flex-1 flex-col gap-2.5 px-3.5 pt-3.5 pb-3.5 md:px-6"
            : "flex flex-1 flex-col gap-3.5 px-3.5 pb-8 md:px-6"
        }
      >
        {left}
      </div>
    </div>
  );
}
