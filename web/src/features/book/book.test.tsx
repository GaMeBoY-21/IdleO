import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/api/errors";
import { data } from "@/test/fixtures";
import { bookSchema, defaultValues, toRequest, valuesFrom, vramToAsk, type Uploads } from "./form";
import { GpuCard } from "./GpuCard";
import { RequestButton } from "./RequestButton";
import type { Quote, QuoteState } from "./useQuote";
import { readyUpload } from "./useUploads";

const fineTune = data.templateList.find((template) => template.id === "qlora-finetune")!;
const runCode = data.templateList.find((template) => template.id === "run-python")!;
const GIB = 1024 ** 3;

const quote = (overrides: Partial<Quote> = {}): QuoteState => ({
  kind: "quoted",
  quote: {
    holdPaise: 540,
    availablePaise: 46_212,
    enoughCredit: true,
    hostsFitNow: 3,
    pricePaisePerMin: { min: 15, max: 18 },
    ceilingPaisePerMin: 18,
    vramNeededMb: 12_000,
    ...overrides,
  },
});
const model = (bytes: number) => readyUpload("m1", "my-llm", bytes, 4);
const nothing: Uploads = { model: null, data: null };

describe("the request", () => {
  it("leaves the price ceiling to the server unless the renter sets one", () => {
    const values = defaultValues(fineTune);
    expect(values.maxPerHour).toBe("");
    expect(toRequest(fineTune, values, nothing).maxPricePaisePerMin).toBeNull();
    expect(toRequest(fineTune, { ...values, maxPerHour: "7.20" }, nothing).maxPricePaisePerMin).toBe(12);
    expect(bookSchema(fineTune).safeParse({ ...values, maxPerHour: "0.10" }).success).toBe(false);
  });

  it("starts at the preset's default, its parameters set, and the template's default budget chip", () => {
    const values = defaultValues(fineTune);
    expect(values.presets).toEqual({ thoroughness: "standard" });
    expect(values.params.epochs).toBe("3");
    expect(values.budget).toBe("100.00");
    expect(values.otherBudget).toBe(false);
  });

  it("sends each input by name: a ready upload, or a link; never one still going", () => {
    const values = defaultValues(fineTune);
    values.inputs.data = { how: "link", url: " https://example.com/chats.jsonl " };
    const going = { ...model(GIB), progress: { ...model(GIB).progress, phase: "uploading" as const } };
    expect(toRequest(fineTune, values, { model: going, data: null }).inputs).toEqual({
      data: { url: "https://example.com/chats.jsonl" },
    });
    expect(toRequest(fineTune, values, { model: model(GIB), data: null }).inputs).toEqual({
      model: { uploadId: "m1" },
      data: { url: "https://example.com/chats.jsonl" },
    });
  });

  it("asks for the GPU memory the model's size needs, a class up, while it's still uploading", () => {
    const values = defaultValues(fineTune);
    // 2,048 + 700 x 14.2 = 11,988 MB: the 12 GB class.
    expect(vramToAsk(fineTune, values, { model: model(Math.round(14.2 * GIB)), data: null })).toBe(12_000);
    // A small model still needs the template's least, rounded up to a class: 8 GB.
    expect(vramToAsk(fineTune, values, { model: model(GIB), data: null })).toBe(8_000);
    // A link's size isn't known: the template's least, unless Advanced asks for more.
    values.inputs.model = { how: "link", url: "https://huggingface.co/me/model" };
    expect(vramToAsk(fineTune, values, nothing)).toBe(6_144);
    expect(vramToAsk(fineTune, { ...values, vramMb: "16000" }, nothing)).toBe(16_000);
  });

  it("asks for the GPU memory the renter chose, when the template asks", () => {
    const values = defaultValues(runCode);
    expect(values.gpuGb).toBe("8");
    expect(toRequest(runCode, { ...values, gpuGb: "16" }, { code: null, data: null }).vramNeededMb).toBe(
      16_000,
    );
  });

  it("fills Run again from the session: its preset, inputs, budget and answers, but not its ceiling", () => {
    const session = { ...data.running, presets: { thoroughness: "thorough" }, maxSpendPaise: 7_500 };
    const values = valuesFrom(fineTune, session);
    expect(values.maxPerHour).toBe("");
    expect(values.presets).toEqual({ thoroughness: "thorough" });
    expect(values.inputs).toEqual({ model: { how: "files", url: "" }, data: { how: "files", url: "" } });
    expect(values).toMatchObject({ budget: "75.00", otherBudget: true });
  });
});

describe("the GPU card", () => {
  it("shows the best free GPU with the memory needed, how many fit, the price per hour and an estimate", () => {
    render(<GpuCard quote={quote()} vramMb={12_000} typicalMinutes={45} laptop />);
    expect(screen.getByText("Best free 12 GB GPU")).toBeInTheDocument();
    expect(screen.getByText("3 free now · picked for you")).toBeInTheDocument();
    // 45 minutes at 15 to 18 paise a minute.
    expect(screen.getByText("≈ ₹7–8")).toBeInTheDocument();
    expect(screen.getByText("₹9.00–10.80/h")).toBeInTheDocument();
  });

  it("says when no GPU is free", () => {
    render(
      <GpuCard quote={{ kind: "none-free", quote: null }} vramMb={16_000} typicalMinutes={null} laptop />,
    );
    expect(screen.getByText("No GPU is free right now")).toBeInTheDocument();
    expect(screen.getByText(/16 GB or more/)).toBeInTheDocument();
  });

  it("says a model too big for every GPU here is too big, never that none is free", () => {
    render(
      <GpuCard
        quote={quote({ vramNeededMb: 28_648, hostsFitNow: 0 })}
        vramMb={28_648}
        typicalMinutes={45}
        laptop
      />,
    );
    expect(screen.getByTestId("gpu-card")).toHaveTextContent(
      "Your model needs about 29 GB of GPU memory.The biggest GPUs on IdleO have 24 GB.",
    );
    expect(screen.queryByText(/No GPU is free/)).toBeNull();
  });
});

describe("Request a GPU", () => {
  const button = (props: Partial<Parameters<typeof RequestButton>[0]>) =>
    render(
      <RequestButton
        quote={quote()}
        uploadingPercent={null}
        tooBig={false}
        busy={false}
        error={null}
        onRequest={() => undefined}
        laptop
        {...props}
      />,
    );

  it("says what's held and what the renter has, from the quote", () => {
    button({});
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeEnabled();
    expect(screen.getByText(/is held while it runs/)).toHaveTextContent(
      "₹5.40 is held while it runs. You have ₹462.12.",
    );
  });

  it("waits for the uploads, saying how far they've got", () => {
    button({ uploadingPercent: 64 });
    expect(screen.getByRole("button", { name: "Waiting for your upload… 64%" })).toBeDisabled();
  });

  it("waits when credit is short, with both figures", () => {
    button({ quote: quote({ enoughCredit: false, availablePaise: 300 }) });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    expect(screen.getByText(/is held while it runs/)).toHaveTextContent(
      "₹5.40 is held while it runs, and you have ₹3.00. Choose a lower budget.",
    );
  });

  it("waits while no GPU is free", () => {
    button({ quote: { kind: "none-free", quote: null } });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    expect(screen.getByText(/No GPU is free right now/)).toBeInTheDocument();
  });

  it("can't be pressed for a model too big for any GPU here", () => {
    button({ tooBig: true });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    expect(screen.getByText(/No GPU here has that much memory/)).toBeInTheDocument();
  });

  it("can be pressed with answers still to give, which points at them", () => {
    const refused = new ApiError(400, "/problems/validation-failed", null, null);
    button({ quote: { kind: "error", error: refused } });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeEnabled();
    expect(screen.getByText(/Answer the questions/)).toBeInTheDocument();
  });
});
