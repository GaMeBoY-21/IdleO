import * as z from "zod/mini";
import type { components } from "@/api/schema.gen";

export type TemplateParam = components["schemas"]["TemplateParam"];
type ParamValue = components["schemas"]["ParamValue"];

/**
 * A template's parameters as a form (ADR 0019, product surface 2.2): generated from the catalog's description, the
 * same one the CLI's flags and the SDK's arguments come from, so nothing is written by hand per template.
 * - `string`: a text input, or a textarea when it may be longer than 200 characters;
 * - `integer` and `number`: numeric inputs with their limits;
 * - `boolean`: a switch;
 * - `choice`: radio buttons, or a select when there are more than five choices.
 * Labels, help, defaults and whether it's required come from the catalog. The server checks again, and its answer
 * is final: a field it refuses comes back on the field (fieldOf).
 */
export type ParamField =
  | { kind: "text" | "textarea"; param: TemplateParam; maxLength: number | null }
  | { kind: "number"; param: TemplateParam; step: "1" | "any"; min: number | null; max: number | null }
  | { kind: "switch"; param: TemplateParam }
  | { kind: "radio" | "select"; param: TemplateParam; choices: string[] };

/** What a parameter's field holds while it's edited: text for text and numbers, so a half-typed "0." is kept. */
export type ParamFormValue = string | boolean;
export type ParamFormValues = Record<string, ParamFormValue>;

const TEXTAREA_OVER = 200;
const SELECT_OVER = 5;

export function paramFields(params: TemplateParam[]): ParamField[] {
  return params.map((param): ParamField => {
    switch (param.type) {
      case "string":
        return {
          kind: (param.maxLength ?? Infinity) > TEXTAREA_OVER ? "textarea" : "text",
          param,
          maxLength: param.maxLength ?? null,
        };
      case "integer":
      case "number":
        return {
          kind: "number",
          param,
          step: param.type === "integer" ? "1" : "any",
          min: param.min ?? null,
          max: param.max ?? null,
        };
      case "boolean":
        return { kind: "switch", param };
      case "choice":
        return {
          kind: param.choices.length > SELECT_OVER ? "select" : "radio",
          param,
          choices: param.choices,
        };
    }
  });
}

/** Each field's starting value: the catalog's default, or empty. */
export function paramDefaults(params: TemplateParam[]): ParamFormValues {
  return Object.fromEntries(
    params.map((param): [string, ParamFormValue] => {
      const value = param.defaultValue;
      if (param.type === "boolean") {
        return [param.name, value === true];
      }
      return [param.name, value === null || value === undefined ? "" : String(value)];
    }),
  );
}

/** A session's resolved parameters back as field values, for Run again. */
export function paramValuesFrom(params: TemplateParam[], given: Record<string, ParamValue>): ParamFormValues {
  const values = paramDefaults(params);
  for (const param of params) {
    const value = given[param.name];
    if (value !== undefined) {
      values[param.name] = param.type === "boolean" ? value === true : String(value);
    }
  }
  return values;
}

const INTEGER = /^-?\d+$/;
const DECIMAL = /^-?(?:\d+\.?\d*|\.\d+)$/;
// The server refuses control characters other than tab and newline in a string (ADR 0019).
// eslint-disable-next-line no-control-regex -- matching control characters is the point
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/;

const range = (min: number | null | undefined, max: number | null | undefined) =>
  min !== null && min !== undefined && max !== null && max !== undefined
    ? `between ${min} and ${max}`
    : min !== null && min !== undefined
      ? `at least ${min}`
      : `at most ${String(max)}`;

/** The checks for one parameter, in the renter's words. */
function paramSchema(param: TemplateParam) {
  const empty = (value: string) => value.trim() === "";
  const required = z.refine<string>(
    (value) => !param.required || !empty(value),
    param.type === "choice"
      ? `Choose a ${param.label.toLowerCase()}.`
      : `Enter a ${param.label.toLowerCase()}.`,
  );
  switch (param.type) {
    case "boolean":
      return z.boolean();
    case "string":
      return z.string().check(
        required,
        z.refine(
          (value) =>
            param.maxLength === null || param.maxLength === undefined || value.length <= param.maxLength,
          {
            message: `Keep it to ${String(param.maxLength)} characters.`,
          },
        ),
        z.refine((value) => !CONTROL.test(value), "Use only ordinary characters, tabs and new lines."),
      );
    case "choice":
      return z.string().check(
        required,
        z.refine((value) => empty(value) || param.choices.includes(value), "Choose one of the options."),
      );
    case "integer":
    case "number": {
      const pattern = param.type === "integer" ? INTEGER : DECIMAL;
      const inRange = (value: number) =>
        (param.min === null || param.min === undefined || value >= param.min) &&
        (param.max === null || param.max === undefined || value <= param.max);
      return z.string().check(
        required,
        z.refine(
          (value) => empty(value) || pattern.test(value.trim()),
          param.type === "integer" ? "Enter a whole number." : "Enter a number.",
        ),
        z.refine(
          (value) => empty(value) || !pattern.test(value.trim()) || inRange(Number(value)),
          `Enter a number ${range(param.min, param.max)}.`,
        ),
      );
    }
  }
}

/** The schema for a template's parameters, one key per parameter. */
export function paramsSchema(params: TemplateParam[]) {
  return z.object(Object.fromEntries(params.map((param) => [param.name, paramSchema(param)])));
}

/**
 * The field values as the API's `params`: numbers as numbers, booleans as booleans, and an optional field left
 * empty left out, so the catalog's default applies.
 */
export function toParams(params: TemplateParam[], values: ParamFormValues): Record<string, ParamValue> {
  const out: Record<string, ParamValue> = {};
  for (const param of params) {
    const value = values[param.name];
    if (typeof value === "boolean") {
      out[param.name] = value;
    } else if (value !== undefined && value.trim() !== "") {
      out[param.name] = param.type === "integer" || param.type === "number" ? Number(value.trim()) : value;
    }
  }
  return out;
}

/**
 * The form field a server's `errors[].field` names (`params.epochs`, `maxPricePaisePerMin`), so its message lands
 * on the right field; null for one the form doesn't have.
 */
export function fieldOf(serverField: string): string | null {
  if (serverField.startsWith("params.")) {
    return serverField;
  }
  const fields: Record<string, string> = {
    maxPricePaisePerMin: "maxPerHour",
    maxSpendPaise: "spendingCap",
    maxRuntimeSeconds: "runtimeHours",
    vramNeededMb: "vramMb",
    "input.url": "inputUrl",
    input: "inputUrl",
  };
  return fields[serverField] ?? null;
}
