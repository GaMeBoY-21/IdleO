import { Tabs } from "radix-ui";
import { type ReactNode } from "react";
import { useLocation, useSearch } from "wouter";
import { cn } from "@/lib/cn";
import { useCan } from "./permissions";

/** One tab: what it needs to be shown, and what it reads once it's opened. */
export type RecordTab = { id: string; label: string; needs: string; render: () => ReactNode };

/**
 * The tabs under a record's header (ADR 0052). The open one lives in the address, so a tab can be linked; only it reads
 * anything; and an admin doesn't see a tab they may not read.
 */
export function RecordTabs({ label, tabs }: { label: string; tabs: RecordTab[] }) {
  const can = useCan();
  const search = new URLSearchParams(useSearch());
  const [location, navigate] = useLocation();
  const allowed = tabs.filter((tab) => can(tab.needs));
  const wanted = search.get("tab");
  const current = allowed.find((tab) => tab.id === wanted) ?? allowed[0];
  if (current === undefined) {
    return null;
  }
  return (
    <Tabs.Root
      value={current.id}
      onValueChange={(next) => {
        search.set("tab", next);
        navigate(`${location}?${search.toString()}`, { replace: true });
      }}
      className="flex flex-col gap-4"
    >
      <Tabs.List aria-label={label} className="flex gap-1 overflow-x-auto border-b border-line">
        {allowed.map((tab) => (
          <Tabs.Trigger
            key={tab.id}
            value={tab.id}
            className={cn(
              "min-h-touch px-4 text-small font-medium whitespace-nowrap text-text-2 md:min-h-9",
              "data-[state=active]:border-b-2 data-[state=active]:border-progress data-[state=active]:font-semibold data-[state=active]:text-text",
            )}
          >
            {tab.label}
          </Tabs.Trigger>
        ))}
      </Tabs.List>
      <Tabs.Content value={current.id}>{current.render()}</Tabs.Content>
    </Tabs.Root>
  );
}
