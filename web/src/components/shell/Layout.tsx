import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * The laptop's frame and grid (web/DESIGN.md "Laptop layout"): the content runs the full width beside the sidebar inside
 * the side padding (14 px on a phone, 32 from 768 px, 40 from 1200 px), with no maximum width and no centred frame;
 * tiles sit on a 12-column grid (6 on a tablet) with a 16 px gap (20 from 1680 px).
 */
export const FRAME = "w-full px-3.5 md:px-8 xl:px-10";
export const GRID = "grid grid-cols-6 gap-4 xl:grid-cols-12 2xl:gap-5";
/** The frame of a screen that fills the window: 32 px above on a laptop, 48 px at the foot. */
export const FIT_FRAME = cn(FRAME, "pt-4 pb-6 md:pt-8 md:pb-12");

/**
 * Two columns from 1200 px: a rail (400 px, or 320 px for Account) with the summary tiles and the rest beside it, filling
 * the screen under the header; on a tablet the two stack. The live session, Hosting's pages, Credit and Account use it.
 */
export function RailColumns({
  left,
  right,
  rail = "lg",
  fit = true,
}: {
  left: ReactNode;
  right: ReactNode;
  rail?: "lg" | "sm";
  /** Fill the window's height (the live session, Hosting); a long page (Credit, Account's lists) is taller instead. */
  fit?: boolean;
}) {
  return (
    <div
      className={cn(
        FIT_FRAME,
        "grid gap-4 2xl:gap-5",
        rail === "lg" ? "xl:cols-rail-lg" : "xl:cols-rail-sm",
        fit && "xl:fit-screen",
      )}
    >
      <div className="flex min-h-0 flex-col gap-4 2xl:gap-5">{left}</div>
      <div className="flex min-h-0 flex-col gap-4 2xl:gap-5">{right}</div>
    </div>
  );
}
