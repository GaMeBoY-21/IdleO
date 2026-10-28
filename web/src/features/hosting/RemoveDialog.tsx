import { Dialog } from "radix-ui";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/Button";

/**
 * Removing a machine, which can't be taken back (ADR 0023): a sheet that names it, never a browser dialog. It stays in
 * your earnings; to rent it out again you add it again.
 */
export function RemoveDialog({
  name,
  open,
  onOpenChange,
  onRemove,
  removing,
  error,
}: {
  name: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onRemove: () => void;
  removing: boolean;
  error: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="appear fixed inset-0 z-30 bg-ink opacity-60" />
        <Dialog.Content
          data-tile=""
          className="appear fixed inset-x-4 bottom-4 z-40 mx-auto flex max-w-narrow flex-col gap-4 rounded-panel bg-panel p-6 border border-line md:top-1/3 md:bottom-auto"
        >
          <Dialog.Title className="text-section">Remove {name}?</Dialog.Title>
          <Dialog.Description className="text-small text-text-2">
            It stops getting new sessions, the host app on it is signed out, and it leaves your machines. What
            it earned stays in your earnings. To rent it out again, add it again.
          </Dialog.Description>
          {error}
          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <Dialog.Close asChild>
              <Button variant="ghost" large>
                Keep it
              </Button>
            </Dialog.Close>
            <Button variant="danger" large busy={removing} onClick={onRemove}>
              Remove {name}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
