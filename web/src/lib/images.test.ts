import { describe, expect, it } from "vitest";
import { checkLine, downloadLine, imageSize, sizeOf } from "./images";

describe("an image's words", () => {
  it("counts sizes as registries do, in decimal", () => {
    expect(imageSize(8_400_000_000)).toBe("8.4 GB");
    expect(imageSize(15_000_000_000)).toBe("15.0 GB");
    expect(imageSize(312_000_000)).toBe("312 MB");
    expect(imageSize(10)).toBe("1 MB");
    expect(sizeOf(312_000_000, 8_400_000_000)).toBe("312 MB of 8.4 GB");
  });

  it("says what a check is doing", () => {
    expect(checkLine({ state: "queued", progress: null })).toBe("Waiting for its check");
    expect(
      checkLine({
        state: "checking",
        progress: { phase: "downloading", doneBytes: 3_100_000_000, totalBytes: 8_400_000_000 },
      }),
    ).toBe("Downloading your image · 3.1 of 8.4 GB");
    expect(
      checkLine({ state: "checking", progress: { phase: "scanning", doneBytes: 0, totalBytes: 0 } }),
    ).toBe("Looking for known vulnerabilities and secrets");
    expect(
      checkLine({ state: "checking", progress: { phase: "blocklist", doneBytes: 0, totalBytes: 0 } }),
    ).toContain("list of software");
  });

  it("says how far a session's image has come down", () => {
    expect(downloadLine({ doneBytes: 3_100_000_000, totalBytes: 8_400_000_000 })).toBe(
      "Downloading your image · 3.1 of 8.4 GB",
    );
    expect(downloadLine({ doneBytes: 0, totalBytes: 0 })).toBe("Downloading your image");
  });
});
