import { useFormContext, useWatch } from "react-hook-form";
import { Field } from "@/components/ui/Field";
import { cn } from "@/lib/cn";
import { formatPaise, parseRupees, rupeeInput } from "@/lib/money";
import type { BookValues } from "./form";
import { StepTitle } from "./StepTitle";

/** "₹50", "₹12.50": a budget chip's amount, whole rupees without the paise. */
const amount = (paise: number) =>
  paise % 100 === 0 ? formatPaise(paise).slice(0, -".00".length) : formatPaise(paise);

/**
 * The budget, the session's spending cap (docs/design/redesign/book-finetune-laptop.html "Budget"): the template's
 * chips, "Stop at ₹100", and "Other amount" for one of the renter's own. A template without chips asks for the
 * amount.
 */
export function Budget({ chips, laptop }: { chips: number[]; laptop: boolean }) {
  const form = useFormContext<BookValues>();
  const [budget, other] = useWatch({ control: form.control, name: ["budget", "otherBudget"] });
  const error = form.formState.errors.budget?.message;
  const chosen = parseRupees(budget);
  const typed = other || chips.length === 0;
  const pick = (paise: number | null) => {
    form.setValue("otherBudget", paise === null, { shouldDirty: true });
    if (paise !== null) {
      form.setValue("budget", rupeeInput(paise), { shouldDirty: true, shouldValidate: true });
    } else {
      // Typing starts from the amount chosen, ready to change, once its field is drawn.
      setTimeout(() => form.setFocus("budget"), 0);
    }
  };
  const chip = (selected: boolean) =>
    cn(
      "flex min-h-touch items-center rounded-full border-2 px-4 text-small font-semibold",
      selected ? "border-text bg-ground" : "border-transparent bg-fill hover:bg-fill-selected",
    );

  return (
    <section aria-labelledby="budget" className="flex flex-col gap-3">
      <StepTitle id="budget" number={null} laptop={laptop}>
        Budget
      </StepTitle>
      {chips.length > 0 && (
        <div role="group" aria-labelledby="budget" className="flex flex-wrap gap-2 tabular">
          {chips.map((paise) => (
            <button
              key={paise}
              type="button"
              aria-pressed={!other && chosen === paise}
              className={chip(!other && chosen === paise)}
              onClick={() => pick(paise)}
            >
              Stop at {amount(paise)}
            </button>
          ))}
          <button type="button" aria-pressed={other} className={chip(other)} onClick={() => pick(null)}>
            Other amount
          </button>
        </div>
      )}
      {typed ? (
        <Field
          label="Stop at, in ₹"
          type="number"
          inputMode="decimal"
          step="0.01"
          min="0.01"
          className="max-w-prose"
          hint="It saves its progress and stops if it ever costs this much."
          error={error}
          {...form.register("budget")}
        />
      ) : (
        <p
          className={cn("text-caption", error ? "text-danger" : "text-text-muted")}
          role={error ? "alert" : undefined}
        >
          {error ?? "It saves its progress and stops if it ever costs this much."}
        </p>
      )}
    </section>
  );
}
