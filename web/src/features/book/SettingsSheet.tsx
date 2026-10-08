import { Dialog } from "radix-ui";
import { useFormContext } from "react-hook-form";
import type { components } from "@/api/schema.gen";
import type { Template } from "@/api/queries";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Select } from "@/components/ui/Inputs";
import { cn } from "@/lib/cn";
import { gigabytes } from "@/lib/gpu";
import { PRICE_STEP } from "@/lib/money";
import { hoursAndMinutes, type BookValues } from "./form";
import { ParamFields } from "./ParamFields";
import { memoryChoices } from "./prices";

type Availability = components["schemas"]["Availability"];

/**
 * Settings (web/DESIGN.md principle 5): a side drawer on a laptop, a bottom sheet on a phone. It holds the template's
 * parameters, generated from the catalog, and the limits: GPU memory, the most per hour (empty: we pick), the
 * spending cap and the longest it may run. `focus` names the field to start at, when the server refused one.
 */
export function SettingsSheet({
  template,
  availability,
  open,
  onOpenChange,
  focus,
  laptop,
}: {
  template: Template;
  availability: Availability | undefined;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  focus: string | null;
  laptop: boolean;
}) {
  const form = useFormContext<BookValues>();
  const errors = form.formState.errors;
  const runtimeError = errors.runtimeHours?.message ?? errors.runtimeMinutes?.message;
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="appear fixed inset-0 z-30 bg-ground/80" />
        <Dialog.Content
          onOpenAutoFocus={(event) => {
            if (focus !== null) {
              event.preventDefault();
              form.setFocus(focus as keyof BookValues);
            }
          }}
          className={cn(
            "rise fixed z-40 flex flex-col overflow-y-auto bg-ground",
            laptop
              ? "inset-y-0 right-0 w-full max-w-drawer shadow-float"
              : "inset-x-0 bottom-0 max-h-dvh rounded-t-sheet shadow-sheet",
          )}
        >
          <div className="flex flex-col gap-6 px-6 pt-6 pb-4">
            {!laptop && (
              <span aria-hidden="true" className="h-1.5 w-10 self-center rounded-full bg-divider" />
            )}
            <div className="flex flex-col gap-1">
              <Dialog.Title className="text-title">Settings</Dialog.Title>
              <Dialog.Description className="text-small text-text-muted">
                {template.name}. Don&apos;t put passwords or tokens here: the job sees these settings, and
                they&apos;re kept with the session.
              </Dialog.Description>
            </div>
            {template.params.length > 0 && <ParamFields params={template.params} />}
            <Select label="GPU memory" error={errors.vramMb?.message} {...form.register("vramMb")}>
              {memoryChoices(template, availability).map((mb) => (
                <option key={mb} value={String(mb)}>
                  At least {gigabytes(mb)}
                </option>
              ))}
            </Select>
            <Field
              label="The most per hour, in ₹ (optional)"
              type="number"
              inputMode="decimal"
              step={PRICE_STEP}
              min={PRICE_STEP}
              placeholder="We pick"
              hint="Leave it empty and we match the best free GPU. Set it, and only GPUs at this price or less."
              error={errors.maxPerHour?.message}
              {...form.register("maxPerHour")}
            />
            <Field
              label="Spending cap, in ₹"
              type="number"
              inputMode="decimal"
              step="0.01"
              min="0.01"
              hint="The session stops, after a last checkpoint, if it costs this much."
              error={errors.spendingCap?.message}
              {...form.register("spendingCap")}
            />
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 text-small font-medium">Longest it may run</legend>
              <div className="grid grid-cols-2 gap-3">
                {(["runtimeHours", "runtimeMinutes"] as const).map((name) => (
                  <Field
                    key={name}
                    label={name === "runtimeHours" ? "Hours" : "Minutes"}
                    type="number"
                    inputMode="numeric"
                    min="0"
                    max={name === "runtimeMinutes" ? "59" : undefined}
                    step="1"
                    aria-invalid={errors[name] ? true : undefined}
                    {...form.register(name)}
                  />
                ))}
              </div>
              <p className={cn("text-caption", runtimeError ? "text-danger" : "text-text-muted")}>
                {runtimeError ?? `Up to ${hoursAndMinutes(template.maxRuntimeCapSeconds)} for this template.`}
              </p>
            </fieldset>
          </div>
          <div className="sticky bottom-0 mt-auto border-t border-divider bg-ground px-6 py-4 pb-safe">
            <Dialog.Close asChild>
              <Button variant="secondary" block large>
                Done
              </Button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
