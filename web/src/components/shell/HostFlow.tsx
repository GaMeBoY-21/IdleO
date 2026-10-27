import { ChevronLeft } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";
import { FlowHeader } from "@/components/shell/AppShell";
import { RailColumns } from "@/components/shell/Layout";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";

/**
 * The frame of a screen about one machine, or about adding one. On a phone a full-screen flow with its back button, as
 * the session screen is. From 768 px the "← Hosting" pill and a dark summary tile (the machine's, or the screen's title
 * and a line about it) sit in the left rail and the rest beside it in white tiles, as the live session does (web/DESIGN.md
 * "Laptop layout"). The summary is drawn once, first, on every size.
 */
export function HostFlow({
  back,
  backLabel,
  summary,
  title,
  lede,
  children,
  className,
}: {
  back: string;
  backLabel: string;
  /** The dark tile of the rail; without one, a tile made of `title` and `lede`. */
  summary?: ReactNode;
  /** The screen's heading, in the rail's tile from 768 px (children then leave it out there). */
  title?: string;
  lede?: string;
  children: ReactNode;
  className?: string;
}) {
  const wide = useWide();
  const pill = (
    <Link
      href={back}
      className="flex min-h-touch items-center gap-1 self-start rounded-full bg-panel px-3.5 text-small font-semibold hover:bg-raised"
    >
      <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={2} />
      {backLabel}
    </Link>
  );
  const head =
    summary ??
    (title && (
      <Tile tone="night" size="lg" className="flex flex-col gap-3 xl:p-7">
        <h1 className="text-question">{title}</h1>
        {lede && <p className="text-body text-on-night-2">{lede}</p>}
      </Tile>
    ));
  if (wide) {
    return (
      <RailColumns
        fit={false}
        left={
          <>
            {pill}
            {head}
          </>
        }
        right={<div className={cn("flex flex-col gap-4 2xl:gap-5", className)}>{children}</div>}
      />
    );
  }
  return (
    <div className="flex flex-1 flex-col">
      <FlowHeader back={back} title={backLabel} />
      <div className="mx-auto flex w-full max-w-card flex-1 flex-col gap-3.5 px-3.5 pt-1 pb-8">
        {title && !summary ? <h1 className="text-title">{title}</h1> : summary}
        {children}
      </div>
    </div>
  );
}
