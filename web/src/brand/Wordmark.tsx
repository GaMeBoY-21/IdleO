import { cn } from "@/lib/cn";
import { brand } from "./brand";

/**
 * The wordmark (web/DESIGN.md "Brand"): the name in lowercase, set heavy and tight in the one typeface, as in the
 * Bento reference (docs/design/bento/). It takes the text colour around it and has no image to load.
 */
export function Wordmark({ className }: { className?: string }) {
  return <span className={cn("text-figure", className)}>{brand.name.toLowerCase()}</span>;
}
