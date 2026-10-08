// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/api/errors";
import { PROBLEM_MESSAGES, problemMessage } from "./problems";

/** Written from the control plane's catalog by ProblemCatalogTests (ADR 0019). */
const published = JSON.parse(
  readFileSync(new URL("../../../api/problems.json", import.meta.url), "utf8"),
) as {
  slug: string;
  status: number;
  title: string;
}[];

describe("the words for problem types", () => {
  it("cover every type the API publishes, and nothing it doesn't", () => {
    expect(Object.keys(PROBLEM_MESSAGES).sort()).toEqual(published.map((problem) => problem.slug).sort());
  });

  it("say what happened and what to do, in sentences", () => {
    for (const message of Object.values(PROBLEM_MESSAGES)) {
      expect(message).toMatch(/^[A-Z].*\.$/);
      expect(message).not.toMatch(/!/);
    }
  });

  it("are picked by type, never by the API's English", () => {
    const error = ApiError.of(402, { type: "/problems/insufficient-credit", title: "Whatever", detail: "x" });
    expect(problemMessage(error)).toBe(PROBLEM_MESSAGES["insufficient-credit"]);
    expect(problemMessage(new TypeError("Failed to fetch"))).toMatch(/Check your connection/);
    expect(problemMessage(ApiError.of(502, "<html>"))).toBe(PROBLEM_MESSAGES["internal-error"]);
  });

  it("keep each invalid field of a validation problem", () => {
    const error = ApiError.of(400, {
      type: "/problems/validation-failed",
      status: 400,
      title: "The request has invalid fields",
      errors: [{ field: "params.epochs", detail: "must be at most 10" }, { nonsense: true }],
    });
    expect(error.errors).toEqual([{ field: "params.epochs", detail: "must be at most 10" }]);
  });
});
