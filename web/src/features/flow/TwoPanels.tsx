import type { ReactNode } from "react";

/**
 * The laptop's two panels (web/DESIGN.md "The layout"): the question and its choices on the left, at most 480 px,
 * with a 1 px divider; a grey panel on the right holding a white card of at most 600 px. Home and Book share it.
 * The card sits at the top of its panel, not in the middle, so a line arriving in it (a price, an upload finishing)
 * only grows it downwards and moves nothing (web/DESIGN.md "Motion": no layout shift); it stays in view while the
 * left panel scrolls. `right` is null while the screen is loading: the grey panel, without a card yet. `bare`: the
 * right panel holds its own blocks, full width, as the live session's route and logs do
 * (docs/design/redesign/live-laptop.html). `wide`: a service's booking screen
 * (docs/design/redesign/book-finetune-laptop.html), its questions up to 720 px and the request card beside them.
 */
export function TwoPanels({
  left,
  right,
  rightLabel,
  bare = false,
  wide = false,
}: {
  left: ReactNode;
  right: ReactNode;
  rightLabel: string;
  bare?: boolean;
  wide?: boolean;
}) {
  return (
    <div className="flex flex-1">
      <div
        className={
          wide
            ? "flex w-full max-w-form min-w-0 shrink flex-col gap-8 border-r border-divider px-10 pt-8 pb-10"
            : "flex w-full max-w-rail min-w-0 shrink-0 flex-col gap-8 border-r border-divider px-8 pt-10 pb-8"
        }
      >
        {left}
      </div>
      <section
        aria-label={rightLabel}
        className={
          bare
            ? "flex min-w-0 flex-1 flex-col gap-6 bg-fill p-10"
            : "flex min-w-0 flex-1 justify-center bg-fill p-12"
        }
      >
        {bare
          ? right
          : right !== null && (
              <div
                className={
                  wide
                    ? "sticky top-24 flex h-fit w-full max-w-request flex-col gap-5 rounded-panel bg-ground p-7"
                    : "sticky top-24 flex h-fit w-full max-w-card flex-col gap-8 rounded-panel bg-ground p-10"
                }
              >
                {right}
              </div>
            )}
      </section>
    </div>
  );
}
