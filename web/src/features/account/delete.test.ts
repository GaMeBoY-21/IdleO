import { describe, expect, it } from "vitest";
import { kept, period } from "./DeleteAccount";

describe("deleting the account's periods", () => {
  it("says years for the money records' 8 and days for the rest, as the server gives them", () => {
    expect(period(2922)).toBe("8 years");
    expect(period(365)).toBe("365 days");
    expect(period(30)).toBe("30 days");
    expect(period(1)).toBe("1 day");
  });

  it("names every period in what's kept from the server's figures, never its own", () => {
    const lines = kept({
      request: null,
      blockers: [],
      losses: [],
      waits: [],
      waitingDays: 7,
      freshSignIn: true,
      keptFor: { moneyRecordsDays: 3650, securityLogDays: 400, backupsDays: 14 },
    });
    expect(lines.join(" ")).toContain("for 10 years");
    expect(lines.join(" ")).toContain("for 400 days");
    expect(lines.join(" ")).toContain("up to 14 days");
  });
});
