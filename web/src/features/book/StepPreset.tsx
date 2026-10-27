import { Check } from "lucide-react";
import { useFormContext, useWatch } from "react-hook-form";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { formatEstimate } from "@/lib/money";
import type { BookValues } from "./form";
import { StepTitle } from "./StepTitle";
import type { PresetOption } from "./steps";
import type { QuoteState } from "./useQuote";

/** "≈ ₹6–8": an option's typical minutes at the price range of the GPUs that fit now; null when there's no range. */
export function optionCost(option: PresetOption, quote: QuoteState | null): string | null {
  const range = quote?.kind === "quoted" ? quote.quote.pricePaisePerMin : null;
  if (!range || option.minutes == null) {
    return null;
  }
  return formatEstimate(range.min * option.minutes, range.max * option.minutes);
}

/**
 * A preset question as option cards (docs/design/redesign/book-finetune-laptop.html "3. How thorough?"): each
 * option's name, its typical time and what that costs at today's prices. Choosing one sets the parameters it names,
 * which Advanced still shows and can change; the choice is kept with the session, so Run again asks the same way.
 */
export function StepPreset({
  number,
  name,
  label,
  help,
  options,
  quote,
}: {
  number: number;
  name: string;
  label: string;
  help: string | null;
  options: PresetOption[];
  quote: QuoteState | null;
}) {
  const form = useFormContext<BookValues>();
  const chosen = useWatch({ control: form.control, name: `presets.${name}` });
  const error = form.formState.errors.presets?.[name]?.message;
  const id = `preset-${name}`;
  const choose = (option: PresetOption) => {
    form.setValue(`presets.${name}`, option.id, { shouldDirty: true });
    for (const [param, value] of Object.entries(option.sets)) {
      form.setValue(`params.${param}`, typeof value === "boolean" ? value : String(value), {
        shouldDirty: true,
        shouldValidate: true,
      });
    }
  };

  return (
    <Tile as="section" aria-labelledby={id} className="flex flex-col gap-3">
      <StepTitle id={id} number={number}>
        {label}
      </StepTitle>
      <div role="group" aria-labelledby={id} className="grid grid-cols-3 gap-2 tabular">
        {options.map((option) => {
          const selected = option.id === chosen;
          const cost = optionCost(option, quote);
          return (
            <button
              key={option.id}
              type="button"
              aria-pressed={selected}
              onClick={() => choose(option)}
              className={cn(
                "relative flex min-w-0 flex-col items-start gap-1 rounded-control p-3.5 text-left transition-colors",
                selected ? "bg-volt text-ink" : "bg-canvas hover:bg-line",
              )}
            >
              {selected && (
                <Check aria-hidden="true" className="absolute top-3 right-3 size-4" strokeWidth={3} />
              )}
              <span className="text-body font-semibold break-words">{option.label}</span>
              <span className={cn("text-caption", selected ? "text-ink" : "text-text-2")}>
                {option.minutes == null ? "Varies" : `About ${option.minutes} min`}
              </span>
              {quote?.kind === "loading" ? (
                <SkeletonLine size="small" width="w-12" />
              ) : (
                <span
                  className={cn(
                    "text-small font-semibold",
                    cost === null && (selected ? "text-ink" : "text-text-2"),
                  )}
                >
                  {cost ?? (quote?.kind === "none-free" ? "No GPU free" : "No price yet")}
                </span>
              )}
            </button>
          );
        })}
      </div>
      <p
        className={cn("text-caption", error ? "text-danger" : "text-text-2")}
        role={error ? "alert" : undefined}
      >
        {error ?? help ?? "Typical times, and what they cost on the GPUs free now."}
      </p>
    </Tile>
  );
}
