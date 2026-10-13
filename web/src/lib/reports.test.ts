// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { Dispute } from "@/api/trust";
import { reasonTitle, REPORT_DETAILS_MAX, REPORT_REASONS, reportWords } from "./reports";

const root = new URL("../../../", import.meta.url).pathname;

const dispute = (overrides: Partial<Dispute>): Dispute => ({
  id: "01a1a000-0000-7000-8000-00000000d001",
  sessionId: "01a1a000-0000-7000-8000-00000000a001",
  reason: "failed",
  state: "open",
  createdAt: "2026-10-13T10:00:00Z",
  ...overrides,
});

describe("the reasons for a report", () => {
  it("are the control plane's six, in its order, with its details limit", () => {
    const java = readFileSync(
      `${root}control-plane/src/main/java/com/idleo/controlplane/trust/internal/Disputes.java`,
      "utf8",
    );
    const listed = /REASONS =\s*List\.of\(([^)]*)\)/.exec(java)?.[1];
    expect(listed).toBeDefined();
    const server = [...(listed ?? "").matchAll(/"([a-z_]+)"/g)].map((match) => match[1]);
    expect(REPORT_REASONS.map((reason) => reason.value)).toEqual(server);
    expect(java).toContain(`MAX_DETAILS = ${REPORT_DETAILS_MAX}`);
  });

  it("each have a title and a line under it, and a value the app doesn't know is shown readably", () => {
    for (const reason of REPORT_REASONS) {
      expect(reason.title).not.toBe("");
      expect(reason.detail).not.toBe("");
      expect(reasonTitle(reason.value)).toBe(reason.title);
    }
    expect(reasonTitle("something_new")).toBe("something new");
  });
});

describe("where a report stands", () => {
  it("says an open one will be answered within 2 days", () => {
    expect(reportWords(dispute({}))).toEqual({
      headline: "Your report is open",
      line: "We'll reply within 2 days.",
      resolved: false,
    });
  });

  it("says what a refund was, in rupees from integer paise, with our reason", () => {
    expect(
      reportWords(
        dispute({
          state: "refunded",
          refundPaise: 5_050,
          resolutionReason: "The job failed on our host.",
        }),
      ),
    ).toEqual({
      headline: "Report resolved: ₹50.50 refunded",
      line: "The job failed on our host.",
      resolved: true,
    });
  });

  it("says when it was closed without a refund, and why", () => {
    expect(reportWords(dispute({ state: "not_refunded", resolutionReason: "It ran as it should." }))).toEqual(
      { headline: "Report resolved, no refund", line: "It ran as it should.", resolved: true },
    );
  });
});
