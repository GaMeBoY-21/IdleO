import type { ReactNode } from "react";
import { Button } from "@/components/ui/Button";

/**
 * A list's filters, under its page header (web/DESIGN.md "Page headers"), one way on every site: a row of fields that
 * wraps, ending in Search when the filters are a search that's submitted (`onSubmit`), or a plain row of choices that
 * apply as they're made.
 */
export function FilterBar({ onSubmit, children }: { onSubmit?: () => void; children: ReactNode }) {
  if (!onSubmit) {
    return <div className="flex flex-wrap items-end gap-2">{children}</div>;
  }
  return (
    <form
      role="search"
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      {children}
      <Button type="submit" variant="ghost">
        Search
      </Button>
    </form>
  );
}
