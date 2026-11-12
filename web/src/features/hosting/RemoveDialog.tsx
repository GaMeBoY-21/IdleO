import type { ReactNode } from "react";
import { ConfirmDialog } from "@/components/ui/Sheet";

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
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Remove ${name}?`}
      description="It stops getting new sessions, the host app on it is signed out, and it leaves your machines. What it earned stays in your earnings. To rent it out again, add it again."
      keep="Keep it"
      confirm={`Remove ${name}`}
      onConfirm={onRemove}
      busy={removing}
      error={error}
    />
  );
}
