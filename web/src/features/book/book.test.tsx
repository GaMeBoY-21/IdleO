import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/api/errors";
import { data } from "@/test/fixtures";
import { bookSchema, defaultValues, toRequest, valuesFrom, type BookValues } from "./form";
import { GpuCard } from "./GpuCard";
import { RequestButton } from "./RequestButton";
import { missingParams, settingsSummary } from "./summary";
import type { Quote, QuoteState } from "./useQuote";

const qlora = data.templateList.find((template) => template.id === "qlora-finetune")!;
const echo = data.templateList.find((template) => template.id === "echo-input")!;

const quote = (overrides: Partial<Quote> = {}): QuoteState => ({
  kind: "quoted",
  quote: {
    holdPaise: 540,
    availablePaise: 46_212,
    enoughCredit: true,
    hostsFitNow: 10,
    pricePaisePerMin: { min: 12, max: 18 },
    ceilingPaisePerMin: 18,
    ...overrides,
  },
});

describe("the request", () => {
  it("leaves the price ceiling to the server unless the renter sets one", () => {
    const values = defaultValues(qlora);
    expect(values.maxPerHour).toBe("");
    expect(toRequest(qlora, values, null).maxPricePaisePerMin).toBeNull();
    expect(toRequest(qlora, { ...values, maxPerHour: "7.20" }, null).maxPricePaisePerMin).toBe(12);
  });

  it("accepts an empty most per hour, and refuses one under ₹0.60", () => {
    const values = defaultValues(qlora);
    expect(bookSchema(qlora).safeParse(values).success).toBe(true);
    expect(bookSchema(qlora).safeParse({ ...values, maxPerHour: "0.10" }).success).toBe(false);
  });

  it("doesn't carry an earlier session's ceiling into Run again", () => {
    expect(valuesFrom(qlora, data.completed).maxPerHour).toBe("");
  });
});

describe("the settings row", () => {
  it("names the settings in a few words, then where it stops", () => {
    const values: BookValues = { ...defaultValues(echo), params: { greeting: "hello", repeat: "3" } };
    expect(settingsSummary(echo, values)).toBe("Hello · 3 repeat · stops at ₹500.00");
    expect(settingsSummary(echo, { ...values, maxPerHour: "7.20" })).toBe(
      "Hello · 3 repeat · up to ₹7.20 an hour · stops at ₹500.00",
    );
    expect(settingsSummary(echo, values, true)).toBe("Stops at ₹500.00");
  });

  it("says which required settings are still empty", () => {
    expect(missingParams(echo, defaultValues(echo))).toEqual(["Greeting"]);
  });
});

describe("the GPU card", () => {
  it("shows the best free GPU, how many fit, the price per hour and an estimate", () => {
    render(<GpuCard quote={quote()} vramMb={8_192} typicalSeconds={2_700} laptop />);
    expect(screen.getByText("Best free GPU for this job")).toBeInTheDocument();
    expect(screen.getByText("At least 8 GB · 10 free now · picked for you")).toBeInTheDocument();
    expect(screen.getByText("≈ ₹5–8")).toBeInTheDocument();
    expect(screen.getByText("₹7.20–10.80/h")).toBeInTheDocument();
  });

  it("says when no GPU is free", () => {
    render(
      <GpuCard quote={{ kind: "none-free", quote: null }} vramMb={81_920} typicalSeconds={null} laptop />,
    );
    expect(screen.getByText("No GPU is free right now")).toBeInTheDocument();
    expect(screen.getByText(/At least 80 GB/)).toBeInTheDocument();
  });
});

describe("Request a GPU", () => {
  const button = (props: Partial<Parameters<typeof RequestButton>[0]>) =>
    render(
      <RequestButton
        quote={quote()}
        uploadingPercent={null}
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

  it("waits for the upload, saying how far it has got", () => {
    button({ uploadingPercent: 64 });
    expect(screen.getByRole("button", { name: "Waiting for your upload… 64%" })).toBeDisabled();
  });

  it("waits when credit is short, with both figures", () => {
    button({ quote: quote({ enoughCredit: false, availablePaise: 300 }) });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    expect(screen.getByText(/is held while it runs/)).toHaveTextContent(
      "₹5.40 is held while it runs, and you have ₹3.00.",
    );
  });

  it("waits while no GPU is free", () => {
    button({ quote: { kind: "none-free", quote: null } });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    expect(screen.getByText(/No GPU is free right now/)).toBeInTheDocument();
  });

  it("can be pressed with settings to finish, which opens them", () => {
    const refused = new ApiError(400, "/problems/validation-failed", null, null);
    button({ quote: { kind: "error", error: refused } });
    expect(screen.getByRole("button", { name: "Request a GPU" })).toBeEnabled();
    expect(screen.getByText(/Finish your settings/)).toBeInTheDocument();
  });
});
