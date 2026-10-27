import { Dialog } from "radix-ui";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/Button";

/**
 * Revoking a key, which can't be taken back (ADR 0025): a sheet that names it, never a browser dialog. Anything using
 * it stops working at once.
 */
export function RevokeDialog({
  name,
  open,
  onOpenChange,
  onRevoke,
  revoking,
  error,
}: {
  name: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onRevoke: () => void;
  revoking: boolean;
  error: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="appear fixed inset-0 z-30 bg-primary opacity-40" />
        <Dialog.Content
          data-tile=""
          className="appear fixed inset-x-4 bottom-4 z-40 mx-auto flex max-w-narrow flex-col gap-4 rounded-panel bg-panel p-6 border border-line md:top-1/3 md:bottom-auto"
        >
          <Dialog.Title className="text-section">Revoke {name}?</Dialog.Title>
          <Dialog.Description className="text-small text-text-2">
            Anything using this key stops working at once, and it can&apos;t be brought back. You can make
            another any time.
          </Dialog.Description>
          {error}
          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <Dialog.Close asChild>
              <Button variant="ghost" large>
                Keep it
              </Button>
            </Dialog.Close>
            <Button variant="danger" large busy={revoking} onClick={onRevoke}>
              Revoke {name}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
