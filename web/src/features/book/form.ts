import * as z from "zod/mini";
import type { components } from "@/api/schema.gen";
import type { Session, Template } from "@/api/queries";
import { paisePerMinute, parseRupees, rupeeInput, rupeesPerHour } from "@/lib/money";
import { paramDefaults, paramsSchema, paramValuesFrom, toParams, type ParamFormValues } from "./params";
import { defaultPrice } from "./prices";

export { gigabytes, memoryChoices } from "./prices";

type Availability = components["schemas"]["Availability"];
export type CreateSession = components["schemas"]["CreateSessionRequest"];

/** The booking form, as it's edited: text where the renter types, so nothing they type is lost or rounded. */
export interface BookValues {
  params: ParamFormValues;
  inputKind: "upload" | "url";
  inputUrl: string;
  vramMb: string;
  /** Rupees an hour; the API takes paise a minute (src/lib/money.ts). */
  maxPerHour: string;
  /** Rupees. */
  spendingCap: string;
  runtimeHours: string;
  runtimeMinutes: string;
}

/** The spending cap a booking starts with (product surface 3.2). */
export const DEFAULT_CAP_PAISE = 50_000;

const runtimeParts = (seconds: number) => ({
  runtimeHours: String(Math.floor(seconds / 3600)),
  runtimeMinutes: String(Math.floor((seconds % 3600) / 60)),
});

export function defaultValues(template: Template, availability: Availability | undefined): BookValues {
  return {
    params: paramDefaults(template.params),
    inputKind: template.input.upload ? "upload" : "url",
    inputUrl: "",
    vramMb: String(template.minVramMb),
    maxPerHour: rupeesPerHour(defaultPrice(availability, template.minVramMb)),
    spendingCap: rupeeInput(DEFAULT_CAP_PAISE),
    ...runtimeParts(template.defaultMaxRuntimeSeconds),
  };
}

/** Run again: the form filled from an earlier session of the same template. */
export function valuesFrom(template: Template, session: Session): BookValues {
  return {
    params: paramValuesFrom(template.params, session.params),
    inputKind: session.input?.url ? "url" : template.input.upload ? "upload" : "url",
    inputUrl: session.input?.url ?? "",
    vramMb: String(session.vramNeededMb),
    maxPerHour: rupeesPerHour(session.maxPricePaisePerMin),
    spendingCap: rupeeInput(session.maxSpendPaise),
    ...runtimeParts(Math.min(session.maxRuntimeSeconds, template.maxRuntimeCapSeconds)),
  };
}

const runtimeSeconds = (values: Pick<BookValues, "runtimeHours" | "runtimeMinutes">) =>
  (Number(values.runtimeHours || "0") * 60 + Number(values.runtimeMinutes || "0")) * 60;

const WHOLE = /^\d{1,3}$/;

/** "2 hours", "1 hour 30 minutes", "45 minutes". */
export function hoursAndMinutes(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const parts = [
    hours > 0 ? `${hours} hour${hours === 1 ? "" : "s"}` : "",
    minutes > 0 ? `${minutes} minute${minutes === 1 ? "" : "s"}` : "",
  ];
  return parts.filter(Boolean).join(" ") || "0 minutes";
}

/** Every check the form makes before review; the server makes them again, and its answer is final. */
export function bookSchema(template: Template) {
  const cap = template.maxRuntimeCapSeconds;
  return z
    .object({
      params: paramsSchema(template.params),
      inputKind: z.enum(["upload", "url"]),
      inputUrl: z.string(),
      vramMb: z.string(),
      maxPerHour: z
        .string()
        .check(
          z.refine((value) => (paisePerMinute(value) ?? 0) >= 1, "Enter at least ₹0.60 an hour, in rupees."),
        ),
      spendingCap: z
        .string()
        .check(
          z.refine(
            (value) => (parseRupees(value) ?? 0) >= 1,
            "Enter a spending cap in rupees, at least ₹0.01.",
          ),
        ),
      runtimeHours: z
        .string()
        .check(z.refine((value) => value === "" || WHOLE.test(value), "Enter whole hours.")),
      runtimeMinutes: z
        .string()
        .check(
          z.refine(
            (value) => value === "" || (WHOLE.test(value) && Number(value) < 60),
            "Enter 0 to 59 minutes.",
          ),
        ),
    })
    .check(
      z.refine((values) => runtimeSeconds(values) >= 60 && runtimeSeconds(values) <= cap, {
        message: `Choose between 1 minute and ${hoursAndMinutes(cap)}.`,
        path: ["runtimeHours"],
      }),
      z.refine(
        (values) =>
          values.inputKind !== "url" ||
          (values.inputUrl.trim() === "" && template.input.accepts !== "required") ||
          /^https:\/\/[^\s/]+\.[^\s]*$/i.test(values.inputUrl.trim()),
        { message: "Enter a link that starts with https://", path: ["inputUrl"] },
      ),
    );
}

/** The API's request for these values, and the upload chosen; prices exact, in paise. */
export function toRequest(template: Template, values: BookValues, uploadId: string | null): CreateSession {
  const url = values.inputUrl.trim();
  const input =
    values.inputKind === "upload" ? (uploadId === null ? null : { uploadId }) : url === "" ? null : { url };
  return {
    templateId: template.id,
    params: toParams(template.params, values.params),
    input,
    vramNeededMb: Number(values.vramMb),
    maxPricePaisePerMin: paisePerMinute(values.maxPerHour) ?? 1,
    maxSpendPaise: parseRupees(values.spendingCap) ?? 1,
    maxRuntimeSeconds: runtimeSeconds(values),
  };
}

/**
 * The form's draft, kept per template in this tab (sessionStorage), so a reload or a trip back keeps what was typed;
 * forgotten once booked. Storage can be missing or full; then there's simply no draft.
 */
export interface Draft {
  values: BookValues;
  upload: { id: string; name: string; size: number } | null;
}

const draftKey = (templateId: string) => `idleo.book-draft.${templateId}`;

export function readDraft(templateId: string): Draft | null {
  try {
    const text = sessionStorage.getItem(draftKey(templateId));
    return text ? (JSON.parse(text) as Draft) : null;
  } catch {
    return null;
  }
}

export function saveDraft(templateId: string, draft: Draft): void {
  try {
    sessionStorage.setItem(draftKey(templateId), JSON.stringify(draft));
  } catch {
    // No room, or no storage: the draft just isn't kept.
  }
}

export function forgetDraft(templateId: string): void {
  try {
    sessionStorage.removeItem(draftKey(templateId));
  } catch {
    // Nothing to forget.
  }
}
