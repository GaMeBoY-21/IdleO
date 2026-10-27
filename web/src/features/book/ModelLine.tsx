import type { Template } from "@/api/queries";
import type { BookValues, Uploads } from "./form";
import { classGigabytes, modelGigabytes, neededMb, withSizeArticle } from "./vram";

/**
 * Under the GPU card, for a template that sizes GPU memory from the renter's model (ADR 0022): "Your 14.2 GB model
 * needs a 12 GB GPU", or, for a link, that its size can't be seen. Nothing before a model is given.
 */
export function ModelLine({
  template,
  values,
  uploads,
}: {
  template: Template;
  values: BookValues;
  uploads: Uploads;
}) {
  const rule = template.vramFromInput;
  if (!rule) {
    return null;
  }
  const model = uploads[rule.input];
  let line: string | null = null;
  if (values.inputs[rule.input]?.how === "link") {
    line = values.inputs[rule.input]?.url.trim()
      ? "We can't see a link's size; set GPU memory in Advanced if it's large."
      : null;
  } else if (model && model.progress.phase !== "failed") {
    const gpu = withSizeArticle(classGigabytes(neededMb(template, model.size)));
    line = `Your ${modelGigabytes(model.size)} model needs ${gpu} GPU.`;
  }
  return line === null ? null : <p className="text-caption text-on-night-2 tabular">{line}</p>;
}
