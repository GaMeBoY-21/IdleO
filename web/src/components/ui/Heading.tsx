import type { HTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/**
 * A screen's headline (web/DESIGN.md "Typography"). From 768 px: Archivo at width 125% and weight 900, uppercase, between
 * 32 and 44 px. On a phone, where the screens are flows of panels: the panel title face (Archivo at width 112%, 30 px),
 * in sentence case. One to a screen; the app's panels use `font-title` for theirs.
 */
export function PageHeading({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return (
    // eslint-disable-next-line jsx-a11y/heading-has-content -- the content comes with the props
    <h1
      className={cn(
        "font-title text-display-feature md:font-display md:text-display-page md:uppercase",
        className,
      )}
      {...props}
    />
  );
}
