import type { Template } from "@/api/queries";
import { formatPaise, paisePerMinute, parseRupees } from "@/lib/money";
import type { BookValues } from "./form";

/** How many of a template's parameters the Settings row names; the sheet holds them all. */
const SHOWN_PARAMS = 3;

/** A parameter as a few words: "3 epochs", "qwen2.5-1.5b", "Pack sequences"; null when it says nothing. */
function paramWords(template: Template, name: string, value: string | boolean | undefined): string | null {
  const param = template.params.find((p) => p.name === name);
  if (
    !param ||
    value === undefined ||
    value === false ||
    (typeof value === "string" && value.trim() === "")
  ) {
    return null;
  }
  if (value === true) {
    return param.label;
  }
  return param.type === "integer" || param.type === "number"
    ? `${value.trim()} ${param.label.toLowerCase()}`
    : value.trim();
}

/** The required parameters still empty, by label: the Settings row says so before anything is requested. */
export function missingParams(template: Template, values: BookValues): string[] {
  return template.params
    .filter((param) => param.required && paramWords(template, param.name, values.params[param.name]) === null)
    .map((param) => param.label);
}

/**
 * The Settings row's line (docs/design/redesign/book-laptop.html): "3 epochs · rank 16 · stops at ₹500.00", with
 * the most per hour when the renter set one. `short` is the phone's: only where it stops. It starts with a capital, as a line of its own.
 */
export function settingsSummary(template: Template, values: BookValues, short = false): string {
  const cap = parseRupees(values.spendingCap);
  const stops = cap === null || cap < 1 ? null : `stops at ${formatPaise(cap)}`;
  const line = (words: (string | null)[]) => {
    const text = words.filter((word) => word !== null).join(" · ");
    return text.charAt(0).toUpperCase() + text.slice(1);
  };
  if (short) {
    return line([stops]);
  }
  const perMinute = values.maxPerHour.trim() === "" ? null : paisePerMinute(values.maxPerHour);
  const words = [
    ...template.params
      .map((param) => paramWords(template, param.name, values.params[param.name]))
      .filter((words) => words !== null)
      .slice(0, SHOWN_PARAMS),
    perMinute !== null && perMinute > 0 ? `up to ${formatPaise(perMinute * 60)} an hour` : null,
    stops,
  ];
  return line(words);
}

/** Whether a form field is edited in the Settings sheet, rather than on the screen (the input). */
export const inSheet = (field: string) => field !== "inputUrl" && field !== "inputKind";
