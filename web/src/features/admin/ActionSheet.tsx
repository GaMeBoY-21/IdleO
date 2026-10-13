import { Dialog } from "radix-ui";
import { useId, useState, type ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { problemMessage } from "@/lib/problems";
import { cn } from "@/lib/cn";
import { REASON_MAX, reasonProblem } from "./rules";

/**
 * Every admin action is confirmed here (ADR 0027), never in a browser dialog: what it is, what will change, and why,
 * in at least 5 characters, which goes into the audit log beside the change. The sheet stays open, with the
 * server's words, if the action is refused; it closes once the server has done it.
 */
export function ActionSheet({
  open,
  onOpenChange,
  title,
  change,
  confirm,
  destructive = false,
  extra,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  /** What will change, in a sentence or two, with the figures. */
  change: ReactNode;
  confirm: string;
  destructive?: boolean;
  /** Fields of the action's own, above the reason (an amount, say); null when there are none. */
  extra?: ReactNode;
  /** Does the action; its error is shown in the sheet. */
  onConfirm: (reason: string) => Promise<unknown>;
}) {
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const id = useId();
  const problem = reasonProblem(reason);

  const close = (next: boolean) => {
    if (!next) {
      setReason("");
      setTouched(false);
      setError(null);
    }
    onOpenChange(next);
  };

  const submit = async () => {
    setTouched(true);
    if (problem !== null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onConfirm(reason.trim());
      close(false);
    } catch (failed) {
      setError(failed);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Portal>
        <Dialog.Overlay className="appear fixed inset-0 z-30 bg-primary opacity-40" />
        <Dialog.Content className="appear fixed inset-x-4 bottom-4 z-40 mx-auto flex max-w-narrow flex-col gap-4 rounded-panel bg-ground p-6 shadow-float md:top-1/4 md:bottom-auto">
          <Dialog.Title className="text-section font-bold">{title}</Dialog.Title>
          <Dialog.Description asChild>
            <div className="text-small text-text-muted">{change}</div>
          </Dialog.Description>
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              void submit();
            }}
          >
            {extra}
            <div className="flex flex-col gap-2">
              <label htmlFor={id} className="text-small font-medium">
                Reason
              </label>
              <textarea
                id={id}
                value={reason}
                maxLength={REASON_MAX + 20}
                rows={3}
                onChange={(event) => setReason(event.target.value)}
                onBlur={() => setTouched(true)}
                aria-invalid={touched && problem !== null ? true : undefined}
                aria-describedby={`${id}-message`}
                className={cn(
                  "rounded-control bg-fill px-4 py-3 text-body text-text",
                  touched && problem !== null && "ring-2 ring-danger",
                )}
              />
              <p
                id={`${id}-message`}
                className={cn(
                  "min-h-4.5 text-caption",
                  touched && problem !== null ? "text-danger" : "text-text-muted",
                )}
              >
                {touched && problem !== null ? problem : "Recorded in the audit log with your name."}
              </p>
            </div>
            {error !== null && <Banner tone="danger">{refusal(error)}</Banner>}
            <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
              <Dialog.Close asChild>
                <Button variant="secondary">Cancel</Button>
              </Dialog.Close>
              <Button type="submit" variant={destructive ? "danger" : "primary"} busy={busy}>
                {confirm}
              </Button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** A problem the sheet finds before anything is sent, said as it stands. */
export class SheetProblem extends Error {}

/** What went wrong: the catalog's words, and for a refused action the server's own reason, which names the cause. */
export function refusal(error: unknown): ReactNode {
  if (error instanceof SheetProblem) {
    return error.message;
  }
  const words = problemMessage(error);
  if (error instanceof ApiError && error.detail && error.type !== null) {
    return (
      <>
        {words} <span className="font-medium">{error.detail}</span>
      </>
    );
  }
  return words;
}
