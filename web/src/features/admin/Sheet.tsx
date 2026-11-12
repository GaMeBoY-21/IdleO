import { Dialog } from "radix-ui";
import { useRef, type ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * The console's one dialog shell (ADR 0057): every sheet, confirm, the search box and the keys panel are this, so they
 * all trap focus, give it back when they close, answer Esc and look the same. Nothing else in the console opens a
 * dialog; a lint rule (idleo/admin-one-look) refuses it.
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
        <Dialog.Overlay
          className={cn("appear fixed inset-0 bg-primary opacity-40", above ? "z-above" : "z-overlay")}
        />
        <Dialog.Content
          data-tile=""
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
            "appear fixed mx-auto flex max-w-narrow flex-col bg-panel",
            above ? "z-above" : "z-sheet",
            fullOnPhone
              ? "inset-0 gap-2 p-4 md:inset-x-4 md:top-1/6 md:bottom-auto md:max-h-2/3 md:rounded-panel md:border md:border-line"
              : "inset-x-4 bottom-4 max-h-11/12 gap-4 overflow-y-auto rounded-panel border border-line p-6 md:top-1/4 md:bottom-auto md:max-h-2/3",
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
