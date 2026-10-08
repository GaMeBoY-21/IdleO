// @vitest-environment node
import { describe, expect, it } from "vitest";
import { data } from "@/test/fixtures";
import {
  fieldOf,
  paramDefaults,
  paramFields,
  paramsSchema,
  paramValuesFrom,
  toParams,
  type TemplateParam,
} from "./params";

const qlora = data.templateList.find((template) => template.id === "qlora-finetune")!.params;
const echo = data.templateList.find((template) => template.id === "echo-input")!.params;

const issues = (params: TemplateParam[], values: Record<string, unknown>) =>
  paramsSchema(params)
    .safeParse(values)
    .error?.issues.map((issue) => [issue.path.join("."), issue.message]) ?? [];

describe("the form a template's parameters make", () => {
  it("gives each type its field: text, textarea, number, switch, radio and select", () => {
    expect(paramFields(qlora).map((field) => [field.param.name, field.kind])).toEqual([
      ["base_model", "select"],
      ["epochs", "number"],
      ["learning_rate", "number"],
      ["lora_rank", "radio"],
      ["pack_sequences", "switch"],
      ["prompt_template", "textarea"],
    ]);
    expect(paramFields(echo).map((field) => field.kind)).toEqual(["text", "number"]);
  });

  it("steps whole numbers by one and decimals freely, within the catalog's limits", () => {
    const [, epochs, rate] = paramFields(qlora);
    expect(epochs).toMatchObject({ step: "1", min: 1, max: 10 });
    expect(rate).toMatchObject({ step: "any", min: 0.00001, max: 0.01 });
  });

  it("starts from the catalog's defaults, and from a session's values for Run again", () => {
    expect(paramDefaults(qlora)).toEqual({
      base_model: "qwen2.5-1.5b",
      epochs: "3",
      learning_rate: "0.0002",
      lora_rank: "16",
      pack_sequences: true,
      prompt_template: "",
    });
    expect(paramValuesFrom(echo, { greeting: "hello", repeat: 3 })).toEqual({
      greeting: "hello",
      repeat: "3",
    });
  });

  it("refuses what the server would, in the renter's words", () => {
    expect(issues(echo, { greeting: "", repeat: "1" })).toEqual([["greeting", "Enter a greeting."]]);
    expect(issues(echo, { greeting: "x".repeat(201), repeat: "6" })).toEqual([
      ["greeting", "Keep it to 200 characters."],
      ["repeat", "Enter a number between 1 and 5."],
    ]);
    expect(issues(echo, { greeting: "hi\u0007", repeat: "2.5" })).toEqual([
      ["greeting", "Use only ordinary characters, tabs and new lines."],
      ["repeat", "Enter a whole number."],
    ]);
    expect(issues(qlora, { ...paramDefaults(qlora), learning_rate: "abc", base_model: "gpt-9" })).toEqual([
      ["base_model", "Choose one of the options."],
      ["learning_rate", "Enter a number."],
    ]);
    expect(issues(qlora, paramDefaults(qlora))).toEqual([]);
  });

  it("sends numbers as numbers and leaves out an optional field left empty, so its default applies", () => {
    expect(toParams(echo, { greeting: "hello", repeat: "3" })).toEqual({ greeting: "hello", repeat: 3 });
    expect(toParams(echo, { greeting: "hello", repeat: "" })).toEqual({ greeting: "hello" });
    expect(toParams(qlora, { ...paramDefaults(qlora), pack_sequences: false })).toEqual({
      base_model: "qwen2.5-1.5b",
      epochs: 3,
      learning_rate: 0.0002,
      lora_rank: "16",
      pack_sequences: false,
    });
  });

  it("puts each field the server refuses on the matching form field", () => {
    expect(fieldOf("params.epochs")).toBe("params.epochs");
    expect(fieldOf("maxPricePaisePerMin")).toBe("maxPerHour");
    expect(fieldOf("input.url")).toBe("inputUrl");
    expect(fieldOf("somethingElse")).toBeNull();
  });
});
