import { useFormContext } from "react-hook-form";
import { Field } from "@/components/ui/Field";
import { RadioGroup, Select, Switch, TextArea } from "@/components/ui/Inputs";
import type { BookValues } from "./form";
import { paramFields, type ParamField as Generated, type TemplateParam } from "./params";

/**
 * A template's parameters as fields, all generated from the catalog (src/features/book/params.ts): nothing here
 * knows any one template. Advanced holds the advanced ones; a basic one is a numbered question of its own.
 */
export function ParamFields({ params }: { params: TemplateParam[] }) {
  return (
    <div className="flex flex-col gap-5">
      {paramFields(params).map((field) => (
        <ParamField key={field.param.name} field={field} />
      ))}
    </div>
  );
}

/**
 * One parameter's field. `asked`: it's a question with its own heading, so its label is for screen readers only and
 * the heading's help isn't repeated.
 */
export function ParamField({ field, asked = false }: { field: Generated; asked?: boolean }) {
  const form = useFormContext<BookValues>();
  const { param } = field;
  const errors = form.formState.errors.params ?? {};
  const error = (errors as Record<string, { message?: string } | undefined>)[param.name]?.message;
  const name = `params.${param.name}` as const;
  const label =
    param.required || field.kind === "switch" || asked ? param.label : `${param.label} (optional)`;
  const hint = asked ? undefined : (param.help ?? undefined);
  const common = { label, hideLabel: asked, hint, error };
  switch (field.kind) {
    case "text":
      return (
        <Field
          {...common}
          maxLength={field.maxLength ?? undefined}
          autoComplete="off"
          {...form.register(name)}
        />
      );
    case "textarea":
      return <TextArea {...common} maxLength={field.maxLength ?? undefined} {...form.register(name)} />;
    case "number":
      return (
        <Field
          {...common}
          hint={hint ?? limits(field.min, field.max)}
          type="number"
          inputMode={field.step === "1" ? "numeric" : "decimal"}
          step={field.step}
          min={field.min ?? undefined}
          max={field.max ?? undefined}
          className="max-w-prose"
          {...form.register(name)}
        />
      );
    case "switch":
      return <Switch label={label} hint={hint} error={error} {...form.register(name)} />;
    case "radio":
      return (
        <RadioGroup
          legend={label}
          hideLegend={asked}
          hint={hint}
          error={error}
          options={field.choices.map((choice) => ({ value: choice, label: choice }))}
          inputProps={() => form.register(name)}
        />
      );
    case "select":
      return (
        <Select {...common} {...form.register(name)}>
          {!param.required && <option value="">No preference</option>}
          {field.choices.map((choice) => (
            <option key={choice} value={choice}>
              {choice}
            </option>
          ))}
        </Select>
      );
  }
}

function limits(min: number | null, max: number | null): string | undefined {
  if (min !== null && max !== null) {
    return `From ${min} to ${max}.`;
  }
  if (min !== null) {
    return `At least ${min}.`;
  }
  return max !== null ? `At most ${max}.` : undefined;
}

export type { TemplateParam };
