import { ChevronDown } from "lucide-react";
import { useFormContext, useFormState } from "react-hook-form";
import type { Template } from "@/api/queries";
import { Field } from "@/components/ui/Field";
import { Select } from "@/components/ui/Inputs";
import { cn } from "@/lib/cn";
import { PRICE_STEP } from "@/lib/money";
import { hoursAndMinutes, type BookValues } from "./form";
import { ParamFields } from "./ParamFields";
import { advancedParams, asksGpuMemory } from "./steps";
import { classGigabytes, classMb, GPU_CLASSES_GB } from "./vram";

/** The Advanced row's line: what's in it, "Learning rate, LoRA rank, time limit, the most per hour · defaults set". */
export function advancedSummary(template: Template): string {
  const names = [
    ...advancedParams(template).map((param) => param.label),
    ...(asksGpuMemory(template) ? [] : ["GPU memory"]),
    "time limit",
    "the most per hour",
  ];
  // "Learning rate" reads "learning rate" mid-line; "LoRA rank" stays as it is.
  const inLine = (name: string) =>
    /^[A-Z][a-z]*(\s|$)/.test(name) ? name.charAt(0).toLowerCase() + name.slice(1) : name;
  const line = names
    .map((name, i) => (i === 0 ? name.charAt(0).toUpperCase() + name.slice(1) : inLine(name)))
    .join(", ");
  return `${line} · sensible defaults set`;
}

/**
 * "Advanced" (docs/design/redesign/book-finetune-laptop.html): one collapsed row, opened in place, holding every
 * advanced parameter with its default, GPU memory beyond what the job needs (unless it's asked as a question), the
 * most per hour (empty: we pick) and the time limit. It opens by itself when something in it needs fixing.
 */
export function Advanced({
  template,
  neededMb,
  open,
  onToggle,
}: {
  template: Template;
  neededMb: number;
  open: boolean;
  onToggle: () => void;
}) {
  const form = useFormContext<BookValues>();
  const { errors } = useFormState({ control: form.control });
  const runtimeError = errors.runtimeHours?.message ?? errors.runtimeMinutes?.message;
  const more = GPU_CLASSES_GB.map(classMb).filter((mb) => mb > neededMb);

  return (
    <section className="flex flex-col gap-5">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="advanced"
        onClick={onToggle}
        className="flex w-full items-center gap-3.5 rounded-control border border-divider px-4 py-3.5 text-left hover:bg-fill"
      >
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-body font-semibold">Advanced</span>
          <span className="text-caption text-text-muted">{advancedSummary(template)}</span>
        </span>
        <ChevronDown
          aria-hidden="true"
          className={cn("size-5 shrink-0 transition-transform", open && "rotate-180")}
          strokeWidth={2}
        />
      </button>
      {/* Its fields are drawn only once it's opened: the screen's first paint doesn't wait on them, and their
          values are kept in the form meanwhile. */}
      <div id="advanced" hidden={!open} className="flex flex-col gap-5">
        {open && (
          <>
            {advancedParams(template).length > 0 && <ParamFields params={advancedParams(template)} />}
            {!asksGpuMemory(template) && (
              <Select
                label="GPU memory"
                hint="More than the job needs only narrows the GPUs that fit."
                error={errors.vramMb?.message}
                {...form.register("vramMb")}
              >
                <option value="">What it needs: {classGigabytes(neededMb)}</option>
                {more.map((mb) => (
                  <option key={mb} value={String(mb)}>
                    At least {classGigabytes(mb)}
                  </option>
                ))}
              </Select>
            )}
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
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 text-small font-medium">Time limit</legend>
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
                {runtimeError ??
                  `It stops after this long, up to ${hoursAndMinutes(template.maxRuntimeCapSeconds)}.`}
              </p>
            </fieldset>
          </>
        )}
      </div>
    </section>
  );
}
