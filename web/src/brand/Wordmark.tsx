import { cn } from "@/lib/cn";
import { brand } from "./brand";

/**
 * The wordmark (web/DESIGN.md "Brand"): the name in lowercase, in Archivo at width 125% and weight 900, tight. It takes
 * the text colour around it and has no image to load.
 */
export function Wordmark({ className }: { className?: string }) {
  return <span className={cn("font-display text-display-logo", className)}>{brand.name.toLowerCase()}</span>;
}
