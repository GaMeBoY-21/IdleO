import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * The laptop's frame and grid (web/DESIGN.md "Laptop layout"): the content runs beside the sidebar inside the side padding
 * (16 px on a phone, 32 from 768 px, 40 from 1200 px), at most 1200 px wide and centred in what is left; cards sit on a
 * 12-column grid (6 on a tablet) with a 16 px gap.
 */
export const FRAME = "mx-auto w-full max-w-content px-4 md:px-8 xl:px-10";
export const GRID = "grid grid-cols-6 gap-4 xl:grid-cols-12 2xl:gap-4";
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
        // One column below 1200 px, never wider than the window: a long name or email wraps rather than widening it.
        "grid grid-cols-1 gap-4 2xl:gap-4",
        rail === "lg" ? "xl:cols-rail-lg" : "xl:cols-rail-sm",
        fit && "xl:fit-screen",
      )}
    >
      <div className="flex min-h-0 min-w-0 flex-col gap-4 2xl:gap-4">{left}</div>
      <div className="flex min-h-0 min-w-0 flex-col gap-4 2xl:gap-4">{right}</div>
    </div>
  );
}
