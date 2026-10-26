import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { describe, expect, it } from "vitest";
import { data } from "@/test/fixtures";
import { Advanced, advancedSummary } from "./Advanced";
import { Budget } from "./Budget";
import { defaultValues, type BookValues } from "./form";
import { StepPreset } from "./StepPreset";
import { advancedParams, stepsOf, type Step } from "./steps";
import { readyUpload } from "./useUploads";
import { summaryRows } from "./YourRequest";

const fineTune = data.templateList.find((template) => template.id === "qlora-finetune")!;
const runCode = data.templateList.find((template) => template.id === "run-python")!;
const ownImage = data.templateList.find((template) => template.id === "custom-image")!;

/** A generated piece of the screen, inside a form holding the template's defaults; `values` reads it back. */
function Form({
  initial,
  children,
}: {
  initial: BookValues;
  children: (values: () => BookValues) => ReactNode;
}) {
  const form = useForm<BookValues>({ defaultValues: initial });
  return <FormProvider {...form}>{children(() => form.getValues())}</FormProvider>;
}

describe("a service's screen, from its template", () => {
  it("numbers each input, then each plain question, in the catalog's order", () => {
    expect(stepsOf(fineTune).map((step) => [step.number, step.kind])).toEqual([
      [1, "input"],
      [2, "input"],
      [3, "preset"],
    ]);
    expect(stepsOf(runCode).map((step) => [step.number, step.kind])).toEqual([
      [1, "input"],
      [2, "input"],
      [3, "param"],
      [4, "gpu"],
    ]);
  });

  it("puts every advanced parameter behind Advanced, named in its row", () => {
    expect(advancedParams(fineTune).map((param) => param.name)).toEqual([
      "epochs",
      "learning_rate",
      "lora_rank",
      "pack_sequences",
      "prompt_template",
    ]);
    expect(advancedSummary(fineTune)).toBe(
      "Epochs, learning rate, LoRA rank, pack short examples together, prompt format, GPU memory, time limit, the most per hour · sensible defaults set",
    );
    // Run your code asks for GPU memory as a question, so Advanced doesn't.
    expect(advancedSummary(runCode)).toBe("Time limit, the most per hour · sensible defaults set");
  });

  it("shows a preset's options with their typical time and cost, and choosing one sets its parameters", async () => {
    let read: () => BookValues = () => defaultValues(fineTune);
    const step = stepsOf(fineTune).find((s): s is Extract<Step, { kind: "preset" }> => s.kind === "preset")!;
    const price = {
      kind: "quoted" as const,
      quote: { ...data.quote(null, 10_000)!, pricePaisePerMin: { min: 12, max: 18 } },
    };
    render(
      <Form initial={defaultValues(fineTune)}>
        {(values) => {
          read = values;
          return <StepPreset {...step} quote={price} />;
        }}
      </Form>,
    );
    const quick = screen.getByRole("button", { name: /Quick/ });
    // 15 minutes at 12 to 18 paise a minute: ₹1.80 to ₹2.70.
    expect(quick).toHaveTextContent("QuickAbout 15 min≈ ₹2–3");
    expect(screen.getByRole("button", { name: /Standard/ })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(quick);
    expect(read().presets).toEqual({ thoroughness: "quick" });
    expect(read().params.epochs).toBe("1");
  });

  it("offers the budget chips and Other amount, which asks for the amount", async () => {
    let read: () => BookValues = () => defaultValues(fineTune);
    render(
      <Form initial={defaultValues(fineTune)}>
        {(values) => {
          read = values;
          return <Budget chips={fineTune.budgetChips} />;
        }}
      </Form>,
    );
    const chips = within(screen.getByRole("group", { name: "Budget" })).getAllByRole("button");
    expect(chips.map((chip) => chip.textContent)).toEqual([
      "Stop at ₹50",
      "Stop at ₹100",
      "Stop at ₹500",
      "Other amount",
    ]);
    expect(chips[1]).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(chips[2]!);
    expect(read().budget).toBe("500.00");
    await userEvent.click(chips[3]!);
    expect(screen.getByLabelText("Stop at, in ₹")).toHaveValue(500);
  });

  it("keeps Advanced closed, its fields generated with their defaults", () => {
    const { rerender } = render(
      <Form initial={defaultValues(fineTune)}>
        {() => <Advanced template={fineTune} neededMb={8_000} open={false} onToggle={() => undefined} />}
      </Form>,
    );
    expect(screen.getByRole("button", { name: /Advanced/ })).toHaveAttribute("aria-expanded", "false");
    rerender(
      <Form initial={defaultValues(fineTune)}>
        {() => <Advanced template={fineTune} neededMb={8_000} open onToggle={() => undefined} />}
      </Form>,
    );
    expect(screen.getByLabelText("Epochs (optional)")).toHaveValue(3);
    expect(screen.getByRole("radio", { name: "16" })).toBeChecked();
    expect(screen.getByLabelText("GPU memory")).toHaveDisplayValue("What it needs: 8 GB");
    expect(screen.getByLabelText("The most per hour, in ₹ (optional)")).toHaveValue(null);
  });

  it("sums up the request in rows built from the same steps", () => {
    const values = defaultValues(fineTune);
    values.inputs.data = { how: "link", url: "https://example.com/chats.jsonl" };
    expect(summaryRows(fineTune, values, { model: readyUpload("m", "my-llm-7b", 1, 4), data: null })).toEqual(
      [
        ["Your model", "my-llm-7b/"],
        ["Your training data", "From your link"],
        ["How thorough", "Standard · about 45 min"],
        ["You get", "adapter_model.safetensors and its training log"],
      ],
    );
    expect(summaryRows(runCode, defaultValues(runCode), { code: null, data: null })).toEqual([
      ["Your code", "Not chosen yet"],
      ["Your data", "None"],
      ["Command", "python main.py"],
      ["GPU memory you need", "8 GB"],
      ["You get", "whatever your code writes to outputs/"],
    ]);
  });
});

describe("the template that runs the renter's own image", () => {
  it("asks for the image first, then its own steps, numbered on", () => {
    const steps = stepsOf(ownImage);
    expect(steps[0]).toEqual({ kind: "image", number: 1 });
    expect(steps.map((step) => step.number)).toEqual(steps.map((_, i) => i + 1));
    expect(steps.some((step) => step.kind === "input" && step.input.name === "files")).toBe(true);
    expect(stepsOf(runCode).some((step) => step.kind === "image")).toBe(false);
  });

  it("can't be requested without a checked image, and sends the one chosen", async () => {
    const { bookSchema, toRequest } = await import("./form");
    const values = defaultValues(ownImage);
    expect(bookSchema(ownImage).safeParse(values).success).toBe(false);
    const chosen = { ...values, imageId: "01a1765a-0000-7000-8000-000000000001" };
    expect(bookSchema(ownImage).safeParse(chosen).success).toBe(true);
    expect(toRequest(ownImage, chosen, {}).imageId).toBe("01a1765a-0000-7000-8000-000000000001");
    expect(toRequest(runCode, { ...defaultValues(runCode), imageId: "x" }, {})).not.toHaveProperty("imageId");
    expect(summaryRows(ownImage, chosen, {})[0]).toEqual(["Image", "Your checked image"]);
  });
});
