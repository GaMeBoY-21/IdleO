import type { ReactNode } from "react";
import { FlowHeader } from "@/components/shell/AppShell";
import { TwoPanels } from "@/features/flow/TwoPanels";

/**
 * Book's frame. On a laptop, two panels: the services, or a service's questions, on the left; on the right what
 * happens next, or "Your request" (docs/design/redesign/book-finetune-laptop.html, `service`). On a phone, a flow
 * without the bars: the list of services under a back button, or a service's questions with "Your request" held at
 * the foot as a sheet (part of `left`), its own "← All services" at the top.
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
      <TwoPanels
        wide={service}
        left={left}
        right={right}
        rightLabel={service ? "Your request" : "What happens next"}
      />
    );
  }
  return (
    <div className="flex flex-1 flex-col">
      {!service && <FlowHeader back="/" />}
      <div
        className={
          service
            ? "flex flex-1 flex-col gap-7 px-4 pt-4 md:px-6"
            : "flex flex-1 flex-col gap-5 px-4 pb-8 md:px-6"
        }
      >
        {left}
      </div>
    </div>
  );
}
