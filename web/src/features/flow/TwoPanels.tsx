import type { ReactNode } from "react";

/**
 * The laptop's two panels (web/DESIGN.md "The layout"): the question and its choices on the left, at most 480 px,
 * with a 1 px divider; a grey panel on the right holding a white card of at most 600 px. Home and Book share it.
 * The card sits at the top of its panel, not in the middle, so a line arriving in it (a price, an upload finishing)
 * only grows it downwards and moves nothing (web/DESIGN.md "Motion": no layout shift); it stays in view while the
 * left panel scrolls. `right` is null while the
 * screen is loading: the grey panel, without a card yet.
 */
export function TwoPanels({
  left,
  right,
  rightLabel,
}: {
  left: ReactNode;
  right: ReactNode;
  rightLabel: string;
}) {
  return (
    <div className="flex flex-1">
      <div className="flex w-full max-w-rail min-w-0 shrink-0 flex-col gap-8 border-r border-divider px-8 pt-10 pb-8">
        {left}
      </div>
      <section aria-label={rightLabel} className="flex min-w-0 flex-1 justify-center bg-fill p-12">
        {right !== null && (
          <div className="sticky top-24 flex h-fit w-full max-w-card flex-col gap-8 rounded-panel bg-ground p-10">
            {right}
          </div>
        )}
      </section>
    </div>
  );
}
