import { Dialog } from "radix-ui";
import { useRef, type ReactNode } from "react";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/cn";

/**
 * The one dialog shell on every site (ADR 0057, web/DESIGN.md "Dialogs"): every sheet, confirm, the console's search box
 * and keys panel are this, so they all trap focus, give it back when they close, answer Esc, rise in 200 ms (not at all
 * under reduced motion) and look the same. Nothing else opens a dialog; a lint rule (idleo/one-component) refuses it.
 */
export function Sheet({
  open,
  onOpenChange,
  title,
  titleHidden = false,
  description,
  above = false,
  fullOnPhone = false,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  titleHidden?: boolean;
  /** What it says under its title, read out with it. */
  description?: ReactNode;
  /** Above another sheet: the step-up, which an action's sheet may be waiting on. */
  above?: boolean;
  /** The whole screen on a phone: the search box. */
  fullOnPhone?: boolean;
  children: ReactNode;
}) {
  const cameFrom = useRef<Element | null>(null);
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={cn("appear fixed inset-0 bg-scrim/60", above ? "z-above" : "z-overlay")} />
        <Dialog.Content
          // Nothing here is a Dialog.Trigger, so Radix on its own would drop focus on the page: it goes back where it was.
          onOpenAutoFocus={() => {
            cameFrom.current = document.activeElement;
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            const back = cameFrom.current;
            (back instanceof HTMLElement && back.isConnected
              ? back
              : document.querySelector("main")
            )?.focus();
          }}
          {...(description === undefined ? { "aria-describedby": undefined } : {})}
          className={cn(
            "appear fixed mx-auto flex max-w-narrow flex-col border border-hairline-strong bg-surface-2 shadow-menu",
            above ? "z-above" : "z-sheet",
            fullOnPhone
              ? "inset-0 gap-2 p-4 md:inset-x-4 md:top-1/6 md:bottom-auto md:max-h-2/3 md:rounded-panel"
              : "inset-x-4 bottom-4 max-h-11/12 gap-4 overflow-y-auto rounded-panel p-6 md:top-1/4 md:bottom-auto md:max-h-2/3",
          )}
        >
          <Dialog.Title className={titleHidden ? "sr-only" : "text-section"}>{title}</Dialog.Title>
          {description !== undefined && <Dialog.Description asChild>{description}</Dialog.Description>}
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** What closes the sheet it's in. */
export const SheetClose = Dialog.Close;

/**
 * Asking once more before something that can't be undone or costs something (web/DESIGN.md "Dialogs"): what will happen,
 * the one button that does it, saying what it does ("Stop the session", never "OK"), and the way out ("Keep it running").
 * The button shows it's working and can't be pressed twice; a problem shows above the buttons, where it happened.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirm,
  keep,
  onConfirm,
  busy,
  error,
  tone = "danger",
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description: ReactNode;
  /** What the button does: "Remove Nikki's ROG Strix". */
  confirm: ReactNode;
  /** The way out: "Keep it". */
  keep: string;
  onConfirm: () => void;
  busy: boolean;
  /** What went wrong, in plain words, with what to do next. */
  error?: ReactNode;
  tone?: "danger" | "primary";
  children?: ReactNode;
}) {
  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={<p className="text-small text-ink-subtle">{description}</p>}
    >
      {children}
      {error}
      <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
        <SheetClose asChild>
          <Button variant="ghost" large>
            {keep}
          </Button>
        </SheetClose>
        <Button variant={tone} solid={tone === "danger"} large busy={busy} onClick={onConfirm}>
          {confirm}
        </Button>
      </div>
    </Sheet>
  );
}
