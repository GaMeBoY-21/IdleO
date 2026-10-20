import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { data } from "@/test/fixtures";
import { FilePicker } from "./FilePicker";

const fineTune = data.templateList.find((template) => template.id === "qlora-finetune")!;
const model = fineTune.inputs.find((input) => input.name === "model")!;
const training = fineTune.inputs.find((input) => input.name === "data")!;

/** A file picked inside a folder, as `webkitdirectory` gives it. */
const inFolder = (path: string) => {
  const file = new File(["x"], path.split("/").pop()!);
  Object.defineProperty(file, "webkitRelativePath", { value: `thing/${path}` });
  return file;
};

/** What the picker's hidden input gets when the renter chooses: a folder's files, or a file. */
const choose = (testId: string, files: File[]) =>
  fireEvent.change(screen.getByTestId(testId), { target: { files } });

function setup(input: typeof model) {
  const onChoose = vi.fn();
  render(
    <FilePicker
      input={input}
      upload={null}
      onChoose={onChoose}
      onRemove={() => {}}
      onRetry={() => {}}
      missing={false}
    />,
  );
  return onChoose;
}

describe("choosing the model's folder", () => {
  it("says a PDF isn't a model before a byte is sent, and starts nothing", async () => {
    const onChoose = setup(model);
    choose("model-folder", [inFolder("paper.pdf")]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This folder has no config.json, so it isn't a Hugging Face model. Upload the model's folder, the one with config.json and the weights.",
    );
    expect(onChoose).not.toHaveBeenCalled();
  });

  it("says when the weights are missing", async () => {
    const onChoose = setup(model);
    choose("model-folder", [inFolder("config.json"), inFolder("tokenizer.json")]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This folder has no weights file (.safetensors or .bin).",
    );
    expect(onChoose).not.toHaveBeenCalled();
  });

  it("starts the upload of a real-shaped folder, and clears what it said before", async () => {
    const onChoose = setup(model);
    choose("model-folder", [inFolder("paper.pdf")]);
    await screen.findByRole("alert");

    choose("model-folder", [inFolder("config.json"), inFolder("w.safetensors")]);

    await waitFor(() => expect(onChoose).toHaveBeenCalledOnce());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("choosing the training data", () => {
  it("says a file that isn't JSONL isn't, and takes a .jsonl", async () => {
    const onChoose = setup(training);
    choose("data-file", [new File(["%PDF"], "notes.pdf")]);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This file isn't JSONL. Use a .jsonl file with one example per line.",
    );
    expect(onChoose).not.toHaveBeenCalled();

    choose("data-file", [new File(["{}"], "train.jsonl")]);
    await waitFor(() => expect(onChoose).toHaveBeenCalledOnce());
  });
});
