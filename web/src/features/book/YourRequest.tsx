import type { ReactNode } from "react";
import type { Template } from "@/api/queries";
import { aboutRuntime } from "@/lib/catalog";
import type { BookValues, Uploads } from "./form";
import { chosenOption, stepsOf } from "./steps";

/** A step's label as a summary row's: "How thorough?" reads "How thorough". */
const rowLabel = (label: string) => label.replace(/\?$/, "");

/**
 * The request in a few rows (docs/design/redesign/book-finetune-laptop.html "Your request"): each input as it's
 * given, each answer, and what comes back. Built from the same steps as the questions, so it never says anything
 * they don't.
 */
export function summaryRows(template: Template, values: BookValues, uploads: Uploads): [string, string][] {
  const rows: [string, string][] = [];
  for (const step of stepsOf(template)) {
    if (step.kind === "input") {
      const { name, required } = step.input;
      const choice = values.inputs[name];
      const upload = uploads[name];
      const given =
        choice?.how === "link"
          ? choice.url.trim() === ""
            ? null
            : "From your link"
          : upload && upload.progress.phase !== "failed"
            ? `${upload.name}${upload.folder ? "/" : ""}`
            : null;
      rows.push([rowLabel(step.input.label), given ?? (required ? "Not chosen yet" : "None")]);
    } else if (step.kind === "preset") {
      const option = chosenOption(template, step.name, values.presets[step.name]);
      const about = option?.minutes == null ? null : aboutRuntime(option.minutes * 60);
      rows.push([
        rowLabel(step.label),
        option ? [option.label, about].filter(Boolean).join(" · ") : "Not chosen",
      ]);
    } else if (step.kind === "param") {
      const value = values.params[step.param.name];
      const text = typeof value === "boolean" ? (value ? "Yes" : "No") : (value ?? "").trim();
      rows.push([rowLabel(step.label), text === "" ? "Not given yet" : text]);
    } else {
      rows.push([rowLabel(step.label), values.gpuGb === "" ? "Not chosen" : `${values.gpuGb} GB`]);
    }
  }
  if (template.youGet) {
    rows.push(["You get", template.youGet]);
  }
  return rows;
}

/**
 * "Your request": on a laptop, the white card in the right panel (summary, GPU, button, hold line); on a phone, a
 * sheet held at the foot of the screen with the GPU, the button and the hold line, the answers being just above.
 */
export function YourRequest({
  template,
  values,
  uploads,
  gpu,
  button,
  laptop,
}: {
  template: Template;
  values: BookValues;
  uploads: Uploads;
  gpu: ReactNode;
  button: ReactNode;
  laptop: boolean;
}) {
  if (!laptop) {
    return (
      <section
        aria-label="Your request"
        className="sticky bottom-0 z-10 -mx-4 mt-auto flex flex-col gap-3 rounded-t-sheet bg-ground px-4 pt-4 pb-safe shadow-sheet md:-mx-6 md:px-6"
      >
        {gpu}
        {button}
      </section>
    );
  }
  return (
    <>
      <h2 className="text-title">Your request</h2>
      <dl className="flex flex-col gap-2 text-body">
        {summaryRows(template, values, uploads).map(([label, value]) => (
          <div key={label} className="flex items-baseline justify-between gap-4">
            <dt className="shrink-0 text-text-muted">{label}</dt>
            <dd className="min-w-0 text-right font-semibold break-words">{value}</dd>
          </div>
        ))}
      </dl>
      {gpu}
      {button}
    </>
  );
}
