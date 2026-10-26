import type { components } from "@/api/schema.gen";
import type { Template } from "@/api/queries";
import type { TemplateParam } from "./params";

export type TemplateInput = components["schemas"]["TemplateInput"];
export type TemplateQuestion = components["schemas"]["TemplateQuestion"];
export type PresetOption = components["schemas"]["PresetOption"];

/**
 * A service's booking screen as the catalog describes it (ADR 0022, docs/design/redesign/book-finetune-laptop.html),
 * so nothing is written by hand per service: the renter's own image first, for the template that runs one (ADR 0042),
 * then one numbered step per input, then each plain question in the catalog's
 * order (a preset's option cards, a basic parameter, or the GPU memory the job needs); then the budget, and the one
 * "Advanced" row holding every advanced parameter with its default.
 */
export type Step =
  | { kind: "image"; number: number }
  | { kind: "input"; number: number; input: TemplateInput }
  | {
      kind: "preset";
      number: number;
      name: string;
      label: string;
      help: string | null;
      options: PresetOption[];
    }
  | { kind: "param"; number: number; param: TemplateParam; label: string; help: string | null }
  | { kind: "gpu"; number: number; label: string; help: string | null; choicesGb: number[] };

export function stepsOf(template: Template): Step[] {
  // The renter's own image comes first, for the template that runs one (ADR 0042).
  const steps: Step[] = template.ownImage ? [{ kind: "image", number: 1 }] : [];
  for (const input of template.inputs) {
    steps.push({ kind: "input", number: steps.length + 1, input });
  }
  for (const question of template.questions) {
    const number = steps.length + 1;
    const help = question.help ?? null;
    if (question.kind === "preset" && question.name) {
      steps.push({
        kind: "preset",
        number,
        name: question.name,
        label: question.label,
        help,
        options: question.options,
      });
    } else if (question.kind === "param") {
      const param = template.params.find((p) => p.name === question.param);
      if (param) {
        steps.push({
          kind: "param",
          number,
          param,
          label: question.label || param.label,
          help: help ?? param.help ?? null,
        });
      }
    } else if (question.kind === "gpu_memory") {
      steps.push({ kind: "gpu", number, label: question.label, help, choicesGb: question.choicesGb });
    }
  }
  return steps;
}

/** The parameters behind "Advanced", in the catalog's order. */
export const advancedParams = (template: Template): TemplateParam[] =>
  template.params.filter((param) => param.group === "advanced");

/** Whether the template asks for the GPU memory itself; then Advanced doesn't. */
export const asksGpuMemory = (template: Template) =>
  template.questions.some((question) => question.kind === "gpu_memory");

/** The preset questions, with each one's default option. */
export const presetDefaults = (template: Template): Record<string, string> =>
  Object.fromEntries(
    template.questions.flatMap((question) =>
      question.kind === "preset" && question.name && question.defaultOption
        ? [[question.name, question.defaultOption]]
        : [],
    ),
  );

/** What the chosen options set, by parameter, as the server applies them (a parameter given wins). */
export function presetSets(
  template: Template,
  chosen: Record<string, string>,
): Record<string, string | number | boolean> {
  const sets: Record<string, string | number | boolean> = {};
  for (const question of template.questions) {
    const option = question.options.find((o) => question.name && o.id === chosen[question.name]);
    Object.assign(sets, option?.sets ?? {});
  }
  return sets;
}

/** The option chosen in a preset question, if any. */
export function chosenOption(
  template: Template,
  name: string,
  id: string | undefined,
): PresetOption | undefined {
  return template.questions
    .find((q) => q.kind === "preset" && q.name === name)
    ?.options.find((o) => o.id === id);
}

/** The spending caps offered, in paise, and the one chosen at first (product surface 3.2's ₹500 without chips). */
export const DEFAULT_BUDGET_PAISE = 50_000;
export const budgetOf = (template: Template) => ({
  chips: template.budgetChips,
  initial: template.defaultBudgetPaise ?? DEFAULT_BUDGET_PAISE,
});
