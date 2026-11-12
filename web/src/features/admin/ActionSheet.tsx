import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { problemMessage } from "@/lib/problems";
import { formatDateTime } from "@/lib/time";
import { cn } from "@/lib/cn";
import { Sheet, SheetClose } from "@/components/ui/Sheet";
import { Problem } from "./ui";
import { ADMIN, asAction, type AdminApproval } from "./api";
import { REASON_MAX, reasonProblem } from "./rules";

/** A value as a line of plain words shows it. */
export function shownValue(value: unknown): string {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean"
    ? String(value)
    : "none";
}

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
    .map((field) => `${field}: ${shownValue(before[field])} → ${shownValue(now[field])}`);
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
  typedBecause = "This can't be undone.",
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
  /** What's said before it when it isn't that it can't be undone. */
  typedBecause?: string;
  /** Does the action; its error is shown in the sheet. */
  onConfirm: (reason: string) => Promise<unknown>;
}) {
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [typing, setTyping] = useState("");
  const [stale, setStale] = useState(false);
  const [asked, setAsked] = useState<AdminApproval | null>(null);
  const id = useId();
  const client = useQueryClient();
  const problem = reasonRule(reason);
  const key = useRef("");
  const [opened, setOpened] = useState<object | undefined>(undefined);
  const wasOpen = useRef(false);

  // A new key and a new snapshot of the record each time the sheet opens.
  useEffect(() => {
    if (open && !wasOpen.current) {
      key.current = crypto.randomUUID();
      setOpened(record);
    }
    wasOpen.current = open;
  }, [open, record]);

  const typedRight = typed === undefined || typing.trim() === typed;
  const changes = stale ? whatChanged(opened as Record<string, unknown> | undefined, record) : [];

  const close = (next: boolean) => {
    if (!next) {
      setReason("");
      setTouched(false);
      setError(null);
      setTyping("");
      setStale(false);
      setAsked(null);
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
      const approval = await asAction(key.current, record, () => onConfirm(reason.trim()));
      await client.invalidateQueries({ queryKey: [ADMIN] });
      if (approval === null) {
        close(false);
      } else {
        setAsked(approval);
      }
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
    <Sheet
      open={open}
      onOpenChange={close}
      title={title}
      description={<div className="text-small text-text-2">{change}</div>}
    >
      {asked !== null ? (
        <div className="flex flex-col gap-4">
          <Banner tone="info">
            {asked.solo
              ? `Nobody else can approve this, so it waits and runs at ${formatDateTime(asked.runAfter ?? asked.expiresAt)} unless it's cancelled. The Owner has been told.`
              : "It's waiting for another admin with this permission to approve it. Nothing has changed yet."}
          </Banner>
          <div className="flex justify-end">
            <Button onClick={() => close(false)}>Done</Button>
          </div>
        </div>
      ) : (
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
                "rounded-control bg-panel px-4 py-4 text-body text-text",
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
                {typedBecause} Type <span className="tabular font-bold">{typed}</span> to confirm
              </label>
              <input
                id={`${id}-typed`}
                value={typing}
                autoComplete="off"
                onChange={(event) => setTyping(event.target.value)}
                aria-invalid={touched && !typedRight ? true : undefined}
                aria-describedby={`${id}-typed-message`}
                className={cn(
                  "rounded-control bg-panel px-4 py-4 text-body text-text",
                  touched && !typedRight && "ring-2 ring-danger",
                )}
              />
              {touched && !typedRight && (
                <p id={`${id}-typed-message`} className="text-caption text-danger">
                  Type it exactly as shown.
                </p>
              )}
            </div>
          )}
          {stale && (
            <Banner tone="warning">
              This changed since you opened it, and nothing was done.{" "}
              {changes.length > 0 ? `Now: ${changes.join("; ")}.` : "It has been read again."} Check it, then
              confirm again if you still want to.
            </Banner>
          )}
          <Problem error={error} />
          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <SheetClose asChild>
              <Button variant="ghost">Cancel</Button>
            </SheetClose>
            <Button type="submit" variant={destructive ? "danger" : "primary"} busy={busy}>
              {confirm}
            </Button>
          </div>
        </form>
      )}
    </Sheet>
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
