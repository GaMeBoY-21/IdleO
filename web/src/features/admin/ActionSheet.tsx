import { useQueryClient } from "@tanstack/react-query";
import { Dialog } from "radix-ui";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { problemMessage } from "@/lib/problems";
import { cn } from "@/lib/cn";
import { ADMIN, asAction } from "./api";
import { REASON_MAX, reasonProblem } from "./rules";

/** A record's fields that differ between when the sheet opened and now, for the "it changed" notice. */
export function whatChanged(
  before: Record<string, unknown> | undefined,
  now: Record<string, unknown> | undefined,
): string[] {
  if (before === undefined || now === undefined) {
    return [];
  }
  return Object.keys(now)
    .filter((field) => field !== "version")
    .filter((field) => {
      const was = before[field];
      const is = now[field];
      return JSON.stringify(was) !== JSON.stringify(is) && typeof is !== "object" && typeof was !== "object";
    })
    .map((field) => `${field}: ${String(before[field] ?? "none")} → ${String(now[field] ?? "none")}`);
}

/**
 * Every admin action is confirmed here (ADR 0027, ADR 0051), never in a browser dialog: what it is, what will change, and
 * why, in at least 5 characters, which goes into the audit log beside the change. A destructive one also asks the admin
 * to type the target's short name or amount. The action carries the version of the `record` it was decided on and one
 * idempotency key for all its tries. If the record has changed, the server answers 412: the sheet stays open, says what
 * changed, and the admin can decide again on the fresh record. It stays open with the server's words if the action is
 * refused, and closes once the server has done it.
 */
export function ActionSheet({
  open,
  onOpenChange,
  title,
  change,
  confirm,
  destructive = false,
  extra,
  reasonRule = reasonProblem,
  record,
  typed,
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
  /** What's wrong with the reason, when this action asks more of it than the usual 5 characters. */
  reasonRule?: (reason: string) => string | null;
  /** The record this acts on, as read: its version goes in `If-Match`. None for what changes no record. */
  record?: { version: number } & object;
  /** What the admin must type to confirm something that can't be undone: the target's short name or amount. */
  typed?: string;
  /** Does the action; its error is shown in the sheet. */
  onConfirm: (reason: string) => Promise<unknown>;
}) {
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [typing, setTyping] = useState("");
  const [stale, setStale] = useState(false);
  const id = useId();
  const client = useQueryClient();
  const problem = reasonRule(reason);
  const key = useRef("");
  const opened = useRef<Record<string, unknown> | undefined>(undefined);
  const wasOpen = useRef(false);

  // A new key and a new snapshot of the record each time the sheet opens.
  useEffect(() => {
    if (open && !wasOpen.current) {
      key.current = crypto.randomUUID();
      opened.current = record as Record<string, unknown> | undefined;
    }
    wasOpen.current = open;
  }, [open, record]);

  const typedRight = typed === undefined || typing.trim() === typed;
  const changes = stale ? whatChanged(opened.current, record as Record<string, unknown> | undefined) : [];

  const close = (next: boolean) => {
    if (!next) {
      setReason("");
      setTouched(false);
      setError(null);
      setTyping("");
      setStale(false);
    }
    onOpenChange(next);
  };

  const submit = async () => {
    setTouched(true);
    if (problem !== null || !typedRight) {
      return;
    }
    setBusy(true);
    setError(null);
    setStale(false);
    try {
      await asAction(key.current, record, () => onConfirm(reason.trim()));
      close(false);
    } catch (failed) {
      if (failed instanceof ApiError && failed.status === 412) {
        // The record is read again, so what shows is the fresh one, and the next try carries its version.
        setStale(true);
        await client.invalidateQueries({ queryKey: [ADMIN] });
      } else {
        setError(failed);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Portal>
        <Dialog.Overlay className="appear fixed inset-0 z-30 bg-primary opacity-40" />
        <Dialog.Content
          data-tile=""
          className="appear fixed inset-x-4 bottom-4 z-40 mx-auto flex max-w-narrow flex-col gap-4 rounded-panel bg-panel p-6 border border-line md:top-1/4 md:bottom-auto"
        >
          <Dialog.Title className="text-section">{title}</Dialog.Title>
          <Dialog.Description asChild>
            <div className="text-small text-text-2">{change}</div>
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
                  "rounded-control bg-panel px-4 py-3 text-body text-text",
                  touched && problem !== null && "ring-2 ring-danger",
                )}
              />
              <p
                id={`${id}-message`}
                className={cn(
                  "min-h-4.5 text-caption",
                  touched && problem !== null ? "text-danger" : "text-text-2",
                )}
              >
                {touched && problem !== null ? problem : "Recorded in the audit log with your name."}
              </p>
            </div>
            {typed !== undefined && (
              <div className="flex flex-col gap-2">
                <label htmlFor={`${id}-typed`} className="text-small font-medium">
                  This can&apos;t be undone. Type <span className="tabular font-bold">{typed}</span> to
                  confirm
                </label>
                <input
                  id={`${id}-typed`}
                  value={typing}
                  autoComplete="off"
                  onChange={(event) => setTyping(event.target.value)}
                  aria-invalid={touched && !typedRight ? true : undefined}
                  className={cn(
                    "rounded-control bg-panel px-4 py-3 text-body text-text",
                    touched && !typedRight && "ring-2 ring-danger",
                  )}
                />
              </div>
            )}
            {stale && (
              <Banner tone="warning">
                This changed since you opened it, and nothing was done.{" "}
                {changes.length > 0 ? `Now: ${changes.join("; ")}.` : "It has been read again."} Check it,
                then confirm again if you still want to.
              </Banner>
            )}
            {error !== null && <Banner tone="danger">{refusal(error)}</Banner>}
            <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
              <Dialog.Close asChild>
                <Button variant="ghost">Cancel</Button>
              </Dialog.Close>
              <Button
                type="submit"
                variant={destructive ? "danger" : "primary"}
                busy={busy}
                disabled={!typedRight}
              >
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
