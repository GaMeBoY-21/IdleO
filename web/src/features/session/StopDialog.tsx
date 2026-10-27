import { Dialog } from "radix-ui";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/Button";

/**
 * Stopping a running session: the one dialog the app has (web/DESIGN.md principle 5), because it ends something the
 * renter may not be able to start again where it was.
 */
export function StopDialog({
  open,
  onOpenChange,
  onStop,
  stopping,
  error,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onStop: () => void;
  stopping: boolean;
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
          <Dialog.Title className="text-section">Stop this session?</Dialog.Title>
          <Dialog.Description className="text-small text-text-2">
            It saves a checkpoint, then stops. Nothing more is charged once it has stopped, and its outputs so
            far are kept.
          </Dialog.Description>
          {error}
          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <Dialog.Close asChild>
              <Button variant="ghost" large>
                Keep it running
              </Button>
            </Dialog.Close>
            <Button variant="danger" large busy={stopping} onClick={onStop}>
              Stop the session
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
