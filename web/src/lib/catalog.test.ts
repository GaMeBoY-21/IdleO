import { describe, expect, it } from "vitest";
import type { components } from "@/api/schema.gen";
import { serviceTime, serviceTypicalCost } from "./catalog";

type Catalog = components["schemas"]["TemplateList"];

const template = (over: Partial<Catalog["templates"][number]>) =>
  ({
    id: "t",
    service: "s",
    name: "T",
    description: "",
    minVramMb: 8000,
    typicalRuntimeSeconds: 600,
    questions: [],
    ...over,
  }) as Catalog["templates"][number];
const catalog = (templates: Catalog["templates"]): Catalog =>
  ({ services: [{ id: "s", name: "S", hidden: false }], templates }) as unknown as Catalog;
const sizes = [
  { gpuMemoryGb: 12, typicalPricePaisePerHour: { min: 900, max: 1200 } },
  { gpuMemoryGb: 8, typicalPricePaisePerHour: { min: 600, max: 720 } },
];

describe("a service's typical cost (ADR 0058)", () => {
  it("is its typical minutes at the typical price of the smallest size it fits on", () => {
    // 10 minutes at ₹6 to ₹7.20 an hour.
    expect(serviceTypicalCost(catalog([template({})]), "s", sizes)).toBe("typically ₹1.00–1.20");
    expect(serviceTypicalCost(catalog([template({ minVramMb: 9000 })]), "s", sizes)).toBe(
      "typically ₹1.50–2.00",
    );
  });

  it("is nothing when it can't be known: no size fits, or no typical time", () => {
    expect(serviceTypicalCost(catalog([template({ minVramMb: 30_000 })]), "s", sizes)).toBeNull();
    expect(serviceTypicalCost(catalog([template({ typicalRuntimeSeconds: null })]), "s", sizes)).toBeNull();
    expect(serviceTypicalCost(catalog([template({})]), "s", [])).toBeNull();
  });

  it("leaves the typical time as it was", () => {
    expect(serviceTime(catalog([template({})]), "s")).toContain("10");
  });
});
