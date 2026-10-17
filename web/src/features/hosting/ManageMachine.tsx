import { ChevronRight } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { useLocation } from "wouter";
import { useRemove, useRename, type Host } from "@/api/hosting";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Tile } from "@/components/ui/Tile";
import { Field } from "@/components/ui/Field";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";

// The sheet (Radix) isn't needed until Remove is pressed.
const RemoveDialog = lazy(() =>
  import("./RemoveDialog").then((module) => ({ default: module.RemoveDialog })),
);

const MAX_NAME = 100;

/**
 * "Rename or remove" (docs/design/hosting/machine-phone.html): one row that opens in place, with its name to change
 * and Remove, which asks first in a sheet that names the machine. Removing is refused while it runs a session.
 */
export function ManageMachine({ host }: { host: Host }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(host.name);
  const [asking, setAsking] = useState(false);
  const [, navigate] = useLocation();
  const rename = useRename(host.id);
  const remove = useRemove(host.id);
  const trimmed = name.trim();
  const problem =
    trimmed.length === 0
      ? "Give it a name."
      : trimmed.length > MAX_NAME
        ? `Use at most ${MAX_NAME} characters.`
        : undefined;

  return (
    <section aria-label="Rename or remove" className="flex flex-col gap-2.5">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="tile-motion flex min-h-18 w-full items-center gap-3.5 rounded-tile bg-fill px-4.5 py-3.5 text-left"
      >
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-body font-semibold">Rename or remove</span>
          <span className="text-small text-text-muted">Removing it stops all new sessions</span>
        </span>
        <ChevronRight
          aria-hidden="true"
          className={cn("size-5 shrink-0 transition-transform duration-120", open && "rotate-90")}
          strokeWidth={2}
        />
      </button>
      {open && (
        <Tile as="div" className="flex flex-col gap-4">
          <form
            className="flex flex-col gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              if (!problem && trimmed !== host.name) {
                rename.mutate(trimmed);
              }
            }}
          >
            <Field
              label="Name"
              value={name}
              onChange={(event) => {
                setName(event.target.value);
                rename.reset();
              }}
              error={problem}
              hint={rename.isSuccess ? "Saved." : undefined}
              autoComplete="off"
              maxLength={MAX_NAME + 20}
            />
            {rename.isError && <Banner tone="danger">{problemMessage(rename.error)}</Banner>}
            <Button
              type="submit"
              variant="secondary"
              busy={rename.isPending}
              disabled={problem !== undefined || trimmed === host.name}
              className="self-start"
            >
              Save name
            </Button>
          </form>
          <div className="flex flex-col gap-2 border-t border-divider pt-4">
            <Button variant="secondary" block onClick={() => setAsking(true)}>
              Remove this machine
            </Button>
          </div>
        </Tile>
      )}
      {asking && (
        <Suspense fallback={null}>
          <RemoveDialog
            name={host.name}
            open={asking}
            onOpenChange={(next) => {
              setAsking(next);
              remove.reset();
            }}
            onRemove={() =>
              remove.mutate(undefined, { onSuccess: () => navigate("/hosting/", { replace: true }) })
            }
            removing={remove.isPending}
            error={remove.isError ? <Banner tone="danger">{problemMessage(remove.error)}</Banner> : null}
          />
        </Suspense>
      )}
    </section>
  );
}
