import type { ReactNode } from "react";
import { ConfirmDialog } from "@/components/ui/Sheet";

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
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Revoke ${name}?`}
      description="Anything using this key stops working at once, and it can't be brought back. You can make another any time."
      keep="Keep it"
      confirm={`Revoke ${name}`}
      onConfirm={onRevoke}
      busy={revoking}
      error={error}
    />
  );
}
