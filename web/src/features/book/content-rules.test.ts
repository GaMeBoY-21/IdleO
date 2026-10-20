// @vitest-environment node
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import { describe, expect, it } from "vitest";
import { data } from "@/test/fixtures";
import { type Chosen } from "./chosen-files";
import { inspect, matches, problem, UNREADABLE_ZIP, zipNames } from "./content-rules";

const root = new URL("../../../../", import.meta.url).pathname;
const fineTune = data.templateList.find((template) => template.id === "qlora-finetune")!;
const model = fineTune.inputs.find((input) => input.name === "model")!;
const training = fineTune.inputs.find((input) => input.name === "data")!;

const NO_CONFIG =
  "This folder has no config.json, so it isn't a Hugging Face model. Upload the model's folder, the one with config.json and the weights.";
const NO_WEIGHTS =
  "This folder has no weights file (.safetensors or .bin). Upload the model's folder, the one with config.json and the weights.";
const NOT_JSONL = "This file isn't JSONL. Use a .jsonl file with one example per line.";

const chosen = (paths: string[], folder = paths.length > 1): Chosen => ({
  name: "thing",
  folder,
  files: paths.map((path) => ({ path, file: new File(["x"], path.split("/").pop()!) })),
});

/** A zip of these names, stored: local headers with no content, then the central directory and its end record. */
function zip(names: string[], zip64 = false): File {
  const encoder = new TextEncoder();
  const entries = names.map((name) => encoder.encode(name));
  const directory = entries.map((name) => {
    const bytes = new Uint8Array(46 + name.length);
    const view = new DataView(bytes.buffer);
    view.setUint32(0, 0x02014b50, true);
    view.setUint16(28, name.length, true);
    bytes.set(name, 46);
    return bytes;
  });
  const length = directory.reduce((sum, entry) => sum + entry.length, 0);
  const head = new Uint8Array(64); // stands in for the files' own bytes, which are never read
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(10, zip64 ? 0xffff : names.length, true);
  end.setUint32(12, zip64 ? 0xffffffff : length, true);
  end.setUint32(16, zip64 ? 0xffffffff : head.length, true);
  const parts: BlobPart[] = [head, ...directory];
  if (zip64) {
    const record = new DataView(new ArrayBuffer(56));
    record.setUint32(0, 0x06064b50, true);
    record.setBigUint64(32, BigInt(names.length), true);
    record.setBigUint64(40, BigInt(length), true);
    record.setBigUint64(48, BigInt(head.length), true);
    const locator = new DataView(new ArrayBuffer(20));
    locator.setUint32(0, 0x07064b50, true);
    locator.setBigUint64(8, BigInt(head.length + length), true);
    parts.push(record.buffer, locator.buffer);
  }
  parts.push(end.buffer);
  return new File(parts, "model.zip");
}

describe("what a fine-tune's model and data must hold", () => {
  it("looks for a file's name by suffix, depth and case, as the control plane does", () => {
    const config = model.mustContain[0]!;
    expect(matches(config, "config.json")).toBe(true);
    expect(matches(config, "llm/Config.JSON")).toBe(true);
    expect(matches(config, "a/b/config.json")).toBe(false);
    expect(matches(config, "my-config.json")).toBe(false);
    const weights = model.mustContain[1]!;
    expect(matches(weights, "a/b/c/model-0001.SafeTensors")).toBe(true);
    expect(matches(weights, "pytorch_model.bin")).toBe(true);
    expect(matches(weights, "weights.safetensors.txt")).toBe(false);
  });

  it("says a PDF isn't a model, a folder without weights has none, and a real folder is fine", () => {
    expect(problem(model, ["paper.pdf"])).toBe(NO_CONFIG);
    expect(problem(model, ["tokenizer.json", "w.safetensors"])).toBe(NO_CONFIG);
    expect(problem(model, ["config.json", "tokenizer.json"])).toBe(NO_WEIGHTS);
    expect(problem(model, ["a/b/config.json", "a/b/w.safetensors"])).toBe(NO_CONFIG);
    expect(
      problem(model, ["config.json", "model-00001-of-00002.safetensors", "model-00002-of-00002.safetensors"]),
    ).toBeNull();
    expect(problem(model, ["my-llm/config.json", "my-llm/pytorch_model.bin"])).toBeNull();
  });

  it("says a file that isn't JSONL isn't, and takes .jsonl and .json in any case", () => {
    expect(problem(training, ["notes.pdf"])).toBe(NOT_JSONL);
    expect(problem(training, ["train.jsonl"])).toBeNull();
    expect(problem(training, ["Train.JSON"])).toBeNull();
  });

  it("checks what was chosen, a folder by its files and a zip by what it holds", async () => {
    expect(await inspect(model, chosen(["paper.pdf"], false))).toBe(NO_CONFIG);
    expect(await inspect(model, chosen(["config.json", "w.safetensors"]))).toBeNull();
    const good = zip(["llm/", "llm/config.json", "llm/w.safetensors", "__MACOSX/llm/._config.json"]);
    expect(
      await inspect(model, { name: "m", folder: false, files: [{ path: "model.zip", file: good }] }),
    ).toBeNull();
    const noConfig = zip(["llm/w.safetensors"]);
    expect(
      await inspect(model, { name: "m", folder: false, files: [{ path: "m.zip", file: noConfig }] }),
    ).toBe(NO_CONFIG);
    const noWeights = zip(["llm/config.json"]);
    expect(
      await inspect(model, { name: "m", folder: false, files: [{ path: "m.zip", file: noWeights }] }),
    ).toBe(NO_WEIGHTS);
    expect(await inspect(training, chosen(["notes.pdf"], false))).toBe(NOT_JSONL);
  });

  it("reads a zip64 zip's names, and says plainly when a zip can't be read", async () => {
    expect(await zipNames(zip(["a/config.json", "a/w.bin"], true))).toEqual(["a/config.json", "a/w.bin"]);
    const broken = new File(["this is a paper, not a zip, however it's named".repeat(3)], "model.zip");
    expect(
      await inspect(model, { name: "m", folder: false, files: [{ path: "model.zip", file: broken }] }),
    ).toBe(UNREADABLE_ZIP);
  });

  it("has no rules on an input that doesn't need any", async () => {
    const code = data.templateList.find((template) => template.id === "run-python")!.inputs[0]!;
    expect(await inspect(code, chosen(["main.py"], false))).toBeNull();
  });
});

describe("the words are the catalog's", () => {
  it.each(["templates/development.yaml", "templates/development-real.yaml"])(
    "in the fixtures, as in %s",
    (file) => {
      const catalog = parse(readFileSync(join(root, file), "utf8")) as {
        templates: {
          service: string;
          inputs?: {
            name: string;
            must_contain?: { any_of: string[]; max_depth?: number; message: string }[];
            accepts_extensions?: string[];
            not_accepted?: string;
          }[];
        }[];
      };
      const finetune = catalog.templates.find((template) => template.service === "fine-tune")!;
      const rules = (name: string) => {
        const input = finetune.inputs!.find((found) => found.name === name)!;
        return input.accepts_extensions
          ? [
              {
                anyOf: input.accepts_extensions.map((ext) => `*${ext}`),
                maxDepth: null,
                message: input.not_accepted!,
              },
            ]
          : input.must_contain!.map((rule) => ({
              anyOf: rule.any_of,
              maxDepth: rule.max_depth ?? null,
              message: rule.message,
            }));
      };
      expect(model.mustContain).toEqual(rules("model"));
      expect(training.mustContain).toEqual(rules("data"));
    },
  );
});
