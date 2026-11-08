import { describe, expect, it } from "vitest";
import { ApiError } from "./errors";
import { isPauseRefusal } from "./status";
import { createQueryClient } from "./queries";
import { STATUS_KEY } from "./status";

describe("a refusal because part of IdleO is paused", () => {
  it("is a paused or maintenance problem, and nothing else", () => {
    expect(isPauseRefusal(new ApiError(503, "/problems/paused", "Paused", "Bookings are paused: …"))).toBe(
      true,
    );
    expect(isPauseRefusal(new ApiError(503, "/problems/maintenance", "Down for maintenance", "…"))).toBe(
      true,
    );
    expect(
      isPauseRefusal(new ApiError(503, "/problems/storage-unavailable", "Storage unavailable", "…")),
    ).toBe(false);
    expect(isPauseRefusal(new Error("network"))).toBe(false);
  });

  it("asks for the status again at once, so the banner says what's paused", async () => {
    const client = createQueryClient();
    client.setQueryData(STATUS_KEY, { maintenance: false, paused: [] });
    await client
      .query({
        queryKey: ["a-booking"],
        queryFn: () =>
          Promise.reject(new ApiError(503, "/problems/paused", "Paused", "Bookings are paused: …")),
        retry: false,
      })
      .catch(() => undefined);
    expect(client.getQueryState(STATUS_KEY)?.isInvalidated).toBe(true);
  });
});
