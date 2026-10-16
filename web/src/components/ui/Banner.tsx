import { AlertTriangle, Info } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * A line of status above or inside a view: a warning, an error, or plain information. Banners push the content below
 * them down; they never cover it (web/DESIGN.md "Motion", Nikki, Oct 8).
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
      className={cn(
        "flex items-start gap-3 rounded-tile px-4.5 py-3.5 text-small",
        tone === "warning" && "bg-warning-soft text-warning",
        tone === "danger" && "bg-danger-soft text-danger",
        tone === "info" && "bg-fill text-text",
      )}
    >
      <Icon aria-hidden="true" className="mt-px size-5 shrink-0" strokeWidth={1.75} />
      <div className="flex-1">{children}</div>
      {action}
    </div>
  );
}
