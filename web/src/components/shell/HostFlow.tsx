import { ChevronLeft } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";
import { FlowHeader } from "@/components/shell/AppShell";
import { cn } from "@/lib/cn";

/**
 * The frame of a screen about one machine, or about adding one: on a phone a full-screen flow with its back button, as
 * the session screen is; on a laptop the same column with a "← Hosting" link above it, as Book has "← All services".
 */
export function HostFlow({
  back,
  backLabel,
  children,
  className,
}: {
  back: string;
  backLabel: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className="flex flex-1 flex-col">
      <FlowHeader back={back} title={backLabel} />
      <div
        className={cn(
          "mx-auto flex w-full max-w-card flex-1 flex-col gap-6 px-4 pt-1 pb-8 md:px-6 md:pt-8",
          className,
        )}
      >
        <Link
          href={back}
          className="hidden min-h-touch items-center gap-1 self-start text-small font-semibold hover:underline md:flex"
        >
          <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={2} />
          {backLabel}
        </Link>
        {children}
      </div>
    </div>
  );
}
