import type { ReactNode } from "react";
import { FlowHeader } from "@/components/shell/AppShell";
import { Tile } from "@/components/ui/Tile";

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
      <div className="mx-auto flex w-full max-w-content flex-1 items-start gap-6 px-6 pt-2 pb-10">
        <div className="flex min-w-0 flex-1 flex-col gap-3.5">{left}</div>
        <section
          aria-label={service ? "Your request" : "What happens next"}
          className="sticky top-20 flex w-request shrink-0 flex-col"
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
