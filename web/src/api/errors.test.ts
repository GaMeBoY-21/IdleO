import { describe, expect, it } from "vitest";
import { ApiError } from "./errors";

describe("an error from the API", () => {
  it("keeps the problem type the views branch on", () => {
    const error = ApiError.of(402, {
      type: "/problems/insufficient-credit",
      title: "Not enough credit",
      status: 402,
      detail: "Booking holds 300 paise, but only 100 paise of credit is available.",
    });
    expect(error.type).toBe("/problems/insufficient-credit");
    expect(error.status).toBe(402);
    expect(error.message).toBe("Booking holds 300 paise, but only 100 paise of credit is available.");
  });

  it("has no type when something in between answered", () => {
    expect(ApiError.of(502, "<html>Bad gateway</html>").type).toBeNull();
    expect(ApiError.of(400, { type: "about:blank" }).type).toBeNull();
  });
});
