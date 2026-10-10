import * as z from "zod/mini";
import type { components } from "@/api/schema.gen";
import type { Session, Template } from "@/api/queries";
import { paisePerMinute, parseRupees, rupeeInput } from "@/lib/money";
import { paramDefaults, paramsSchema, paramValuesFrom, toParams, type ParamFormValues } from "./params";
import { asksGpuMemory, budgetOf, presetDefaults, presetSets } from "./steps";
import type { InputUpload } from "./useUploads";
import { classMb, neededMb } from "./vram";

export type CreateSession = components["schemas"]["CreateSessionRequest"];

/** How an input is given: uploaded now, picked from My files (both an upload), or a link the job fetches. */
export type InputHow = "upload" | "files" | "link";
export interface InputChoice {
  how: InputHow;
  url: string;
}

/** The booking form, as it's edited: text where the renter types, so nothing they type is lost or rounded. */
export interface BookValues {
  inputs: Record<string, InputChoice>;
  /** Each preset question's chosen option. */
  presets: Record<string, string>;
  params: ParamFormValues;
  /** The "GPU memory you need" question's answer in GB; "" when the template doesn't ask it. */
  gpuGb: string;
  /** Advanced: more GPU memory than the job needs, in MB; "" takes what it needs. */
  vramMb: string;
  /**
   * Advanced: rupees an hour; the API takes paise a minute (src/lib/money.ts). Empty, as it starts: the server picks
   * the ceiling, the highest price among the GPUs that fit right now (ADR 0021).
   */
  maxPerHour: string;
  /** The budget, the spending cap, in rupees: a chip's amount, or what's typed under "Other amount". */
  budget: string;
  otherBudget: boolean;
  runtimeHours: string;
  runtimeMinutes: string;
}

/** Each input's upload, by name; null where nothing is chosen. */
export type Uploads = Record<string, InputUpload | null>;

const runtimeParts = (seconds: number) => ({
  runtimeHours: String(Math.floor(seconds / 3600)),
  runtimeMinutes: String(Math.floor((seconds % 3600) / 60)),
});

const firstHow = (input: Template["inputs"][number]): InputHow => (input.upload ? "upload" : "link");

export function defaultValues(template: Template): BookValues {
  const presets = presetDefaults(template);
  const gpu = template.questions.find((question) => question.kind === "gpu_memory");
  return {
    inputs: Object.fromEntries(
      template.inputs.map((input) => [input.name, { how: firstHow(input), url: "" }]),
    ),
    presets,
    params: { ...paramDefaults(template.params), ...asFormValues(presetSets(template, presets)) },
    gpuGb: gpu?.defaultGb == null ? "" : String(gpu.defaultGb),
    vramMb: "",
    maxPerHour: "",
    budget: rupeeInput(budgetOf(template).initial),
    otherBudget: false,
    ...runtimeParts(template.defaultMaxRuntimeSeconds),
  };
}

const asFormValues = (values: Record<string, string | number | boolean>): ParamFormValues =>
  Object.fromEntries(
    Object.entries(values).map(([name, value]) => [name, typeof value === "boolean" ? value : String(value)]),
  );

/**
 * Run again: the form filled from an earlier session of the same template, its answers, presets and inputs. Its
 * price ceiling isn't carried over: the session doesn't say whether the renter set it or the server picked it, and
 * a new request is matched at today's prices, like a ride. Its uploads come back through Prepared.tsx.
 */
export function valuesFrom(template: Template, session: Session): BookValues {
  const values = defaultValues(template);
  const chips = budgetOf(template).chips;
  const gpu = asksGpuMemory(template);
  return {
    ...values,
    inputs: Object.fromEntries(
      template.inputs.map((input) => {
        const given = session.inputs[input.name];
        return [
          input.name,
          given?.url
            ? { how: "link", url: given.url }
            : { how: given?.uploadId ? "files" : firstHow(input), url: "" },
        ];
      }),
    ),
    presets: { ...values.presets, ...session.presets },
    params: paramValuesFrom(template.params, session.params),
    gpuGb: gpu ? String(Math.round(session.vramNeededMb / 1_000)) : "",
    vramMb: !gpu && session.vramNeededMb > template.minVramMb ? String(session.vramNeededMb) : "",
    budget: rupeeInput(session.maxSpendPaise),
    otherBudget: !chips.includes(session.maxSpendPaise),
    ...runtimeParts(Math.min(session.maxRuntimeSeconds, template.maxRuntimeCapSeconds)),
  };
}

const runtimeSeconds = (values: Pick<BookValues, "runtimeHours" | "runtimeMinutes">) =>
  (Number(values.runtimeHours || "0") * 60 + Number(values.runtimeMinutes || "0")) * 60;

const WHOLE = /^\d{1,3}$/;
export const LINK = /^https:\/\/[^\s/]+\.[^\s]*$/i;

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

/** Every check the form makes before a quote or a request; the server makes them again, and its answer is final. */
export function bookSchema(template: Template) {
  const cap = template.maxRuntimeCapSeconds;
  const input = z.object({ how: z.enum(["upload", "files", "link"]), url: z.string() }).check(
    z.refine((choice) => choice.how !== "link" || choice.url.trim() === "" || LINK.test(choice.url.trim()), {
      message: "Enter a link that starts with https://",
      path: ["url"],
    }),
  );
  return z
    .object({
      inputs: z.record(z.string(), input),
      presets: z.record(z.string(), z.string()),
      params: paramsSchema(template.params),
      gpuGb: z.string(),
      vramMb: z.string(),
      maxPerHour: z
        .string()
        .check(
          z.refine(
            (value) => value.trim() === "" || (paisePerMinute(value) ?? 0) >= 1,
            "Enter at least ₹0.60 an hour, in rupees, or leave it empty.",
          ),
        ),
      budget: z
        .string()
        .check(
          z.refine((value) => (parseRupees(value) ?? 0) >= 1, "Enter a budget in rupees, at least ₹0.01."),
        ),
      otherBudget: z.boolean(),
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
    );
}

/** The input sizing GPU memory: its upload's size, or null for a link or nothing yet (the template's minimum). */
export function modelBytes(template: Template, values: BookValues, uploads: Uploads): number | null {
  const name = template.vramFromInput?.input;
  if (!name || values.inputs[name]?.how === "link") {
    return null;
  }
  return uploads[name]?.size ?? null;
}

/** The GPU memory to ask for: what the job needs, or more when the renter asked for more. */
export function vramToAsk(template: Template, values: BookValues, uploads: Uploads): number {
  if (values.gpuGb !== "") {
    return Math.max(template.minVramMb, classMb(Number(values.gpuGb)));
  }
  const needed = neededMb(template, modelBytes(template, values, uploads));
  return Math.max(needed, Number(values.vramMb || "0"));
}

/** Whether an input has what it needs: a link typed, or an upload that hasn't failed. */
export function inputGiven(values: BookValues, uploads: Uploads, name: string): boolean {
  const choice = values.inputs[name];
  if (choice?.how === "link") {
    return choice.url.trim() !== "";
  }
  const upload = uploads[name];
  return upload != null && upload.progress.phase !== "failed" && upload.progress.phase !== "cancelled";
}

/**
 * The API's request for these values and uploads, prices exact, in paise. A quote takes the same request with only
 * the inputs ready so far, and the GPU memory the uploads still going will need; leaving "the most per hour" empty
 * leaves the ceiling to the server.
 */
export function toRequest(template: Template, values: BookValues, uploads: Uploads): CreateSession {
  const inputs: NonNullable<CreateSession["inputs"]> = {};
  for (const input of template.inputs) {
    const choice = values.inputs[input.name];
    const ready = uploads[input.name]?.progress;
    if (choice?.how === "link") {
      if (choice.url.trim() !== "") {
        inputs[input.name] = { url: choice.url.trim() };
      }
    } else if (ready?.phase === "ready" && ready.uploadId !== null) {
      inputs[input.name] = { uploadId: ready.uploadId };
    }
  }
  return {
    templateId: template.id,
    params: toParams(template.params, values.params),
    presets: values.presets,
    inputs,
    vramNeededMb: vramToAsk(template, values, uploads),
    maxPricePaisePerMin: values.maxPerHour.trim() === "" ? null : (paisePerMinute(values.maxPerHour) ?? 1),
    maxSpendPaise: parseRupees(values.budget) ?? 1,
    maxRuntimeSeconds: runtimeSeconds(values),
  };
}
