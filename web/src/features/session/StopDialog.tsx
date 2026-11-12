import type { ReactNode } from "react";
import { ConfirmDialog } from "@/components/ui/Sheet";

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
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Stop this session?"
      description="It saves a checkpoint, then stops. Nothing more is charged once it has stopped, and its outputs so far are kept."
      keep="Keep it running"
      confirm="Stop the session"
      onConfirm={onStop}
      busy={stopping}
      error={error}
    />
  );
}
