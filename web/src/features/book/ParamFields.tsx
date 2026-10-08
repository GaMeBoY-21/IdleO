import { useFormContext } from "react-hook-form";
import { Field } from "@/components/ui/Field";
import { RadioGroup, Select, Switch, TextArea } from "@/components/ui/Inputs";
import type { BookValues } from "./form";
import { paramFields, type TemplateParam } from "./params";

/**
 * A template's parameters as fields, all generated from the catalog (src/features/book/params.ts): nothing here
 * knows any one template.
 */
export function ParamFields({ params }: { params: TemplateParam[] }) {
  const form = useFormContext<BookValues>();
  const errors = form.formState.errors.params ?? {};
  const message = (name: string) =>
    (errors as Record<string, { message?: string } | undefined>)[name]?.message;

  return (
    <div className="flex flex-col gap-5">
      {paramFields(params).map((field) => {
        const { param } = field;
        const name = `params.${param.name}` as const;
        const label = param.required || field.kind === "switch" ? param.label : `${param.label} (optional)`;
        const hint = param.help ?? undefined;
        switch (field.kind) {
          case "text":
            return (
              <Field
                key={param.name}
                label={label}
                hint={hint}
                error={message(param.name)}
                maxLength={field.maxLength ?? undefined}
                autoComplete="off"
                {...form.register(name)}
              />
            );
          case "textarea":
            return (
              <TextArea
                key={param.name}
                label={label}
                hint={hint}
                error={message(param.name)}
                maxLength={field.maxLength ?? undefined}
                {...form.register(name)}
              />
            );
          case "number":
            return (
              <Field
                key={param.name}
                label={label}
                hint={hint ?? limits(field.min, field.max)}
                error={message(param.name)}
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
            return (
              <Switch
                key={param.name}
                label={label}
                hint={hint}
                error={message(param.name)}
                {...form.register(name)}
              />
            );
          case "radio":
            return (
              <RadioGroup
                key={param.name}
                legend={label}
                hint={hint}
                error={message(param.name)}
                options={field.choices.map((choice) => ({ value: choice, label: choice }))}
                inputProps={() => form.register(name)}
              />
            );
          case "select":
            return (
              <Select
                key={param.name}
                label={label}
                hint={hint}
                error={message(param.name)}
                {...form.register(name)}
              >
                {!param.required && <option value="">No preference</option>}
                {field.choices.map((choice) => (
                  <option key={choice} value={choice}>
                    {choice}
                  </option>
                ))}
              </Select>
            );
        }
      })}
    </div>
  );
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
