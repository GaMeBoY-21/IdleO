import { AlertTriangle, Info } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * A line of status above or inside a view: a warning, an error, or plain information. A surface-1 card with a hairline,
 * and an icon in the status colour (never colour alone: the words say it too). Banners push the content below them
 * down; they never cover it (web/DESIGN.md "Motion", the product owner, Oct 8).
 */
export function Banner({
  tone,
  children,
  action,
}: {
  tone: "warning" | "danger" | "info";
  children: ReactNode;
  action?: ReactNode;
}) {
  const Icon = tone === "info" ? Info : AlertTriangle;
  return (
    <div
      role={tone === "danger" ? "alert" : "status"}
      className="flex items-start gap-2 rounded-card border border-hairline bg-surface-1 px-4 py-4 text-small text-ink"
    >
      <Icon
        aria-hidden="true"
        className={cn(
          "mt-px size-5 shrink-0",
          tone === "warning" && "text-warning",
          tone === "danger" && "text-danger",
          tone === "info" && "text-info",
        )}
        strokeWidth={1.75}
      />
      <div className="flex-1">{children}</div>
      {action}
    </div>
  );
}
