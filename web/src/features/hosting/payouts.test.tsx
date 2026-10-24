import { render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as data from "../../../e2e/fixtures-payouts";
import { InUse, LatestCheck } from "./PayoutDetails";
import { PayoutRow, Summary, SummarySkeleton } from "./Payouts";
import { BLOCKED, PAN_SHAPE, PAYOUT_STATUS, UPI_SHAPE } from "@/lib/payouts";
import { formatPaise } from "@/lib/money";

const now = new Date("2026-10-07T06:34:00Z").getTime();

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(now);
});
afterEach(() => vi.useRealTimers());

describe("the payouts tile", () => {
  it("shows what's available and pending, the next payday, the year's tax, and what's on its way", () => {
    render(<Summary summary={data.summary} />);
    expect(screen.getByText("Available").nextSibling).toHaveTextContent(
      formatPaise(data.summary.availablePaise),
    );
    expect(screen.getByText("Pending").nextSibling).toHaveTextContent(formatPaise(data.summary.pendingPaise));
    expect(screen.getByText("Tax deducted 2026-27")).toBeInTheDocument();
    expect(screen.getByText(/is on its way to your UPI ID/)).toHaveTextContent(
      formatPaise(data.summary.inFlightPaise),
    );
    expect(screen.getByText(/2 sessions pending/)).toBeInTheDocument();
    expect(screen.getByText("Held while a report about it is open")).toBeInTheDocument();
  });

  it("says why it can't pay, with the way to fix it", () => {
    render(<Summary summary={data.summaryNoPan} />);
    expect(screen.getByText(BLOCKED["no-pan"]!, { exact: false })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Payout details" })[0]).toHaveAttribute(
      "href",
      "/hosting/payout-details/",
    );
  });

  it("says nothing is pending in the line a pending list takes, so the tile is the same height", () => {
    render(<Summary summary={{ ...data.summary, pending: [], pendingPaise: 0 }} />);
    expect(screen.getByText("Nothing pending.")).toHaveClass("min-h-touch");
  });

  it("has a skeleton announced as loading", () => {
    render(<SummarySkeleton />);
    expect(screen.getByRole("status", { name: "Loading your payouts" })).toBeInTheDocument();
  });
});

describe("a payout in the history", () => {
  it("shows a payout that failed with the bank's reason in plain words", () => {
    render(
      <ul>
        <PayoutRow payout={data.payouts[1]!} />
      </ul>,
    );
    expect(screen.getByText("Didn't go through")).toBeInTheDocument();
    expect(screen.getByText(/back in your earnings/)).toBeInTheDocument();
  });

  it("shows the tax taken from a payout", () => {
    render(
      <ul>
        <PayoutRow payout={{ ...data.payouts[2]!, tdsPaise: 20, netPaise: 19_980 }} />
      </ul>,
    );
    expect(screen.getByText(/tax \(section/)).toHaveTextContent("₹200.00 less ₹0.20 tax (section 194-O)");
  });
});

describe("the UPI ID", () => {
  it("shows the name the bank has for a check, before it's used", () => {
    render(
      <LatestCheck
        upi={data.checked}
        threshold={80}
        holdHours={48}
        changing={false}
        busy={false}
        onUse={() => {}}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("asha@okicici is registered to ASHA RAO.");
    expect(screen.getByRole("status")).toHaveTextContent("That matches the name on your PAN.");
    expect(screen.getByRole("button", { name: "Use this UPI ID" })).toBeInTheDocument();
  });

  it("says a low match goes to us, and that a change holds the next payout", () => {
    render(
      <LatestCheck
        upi={{ ...data.checked, nameMatchScore: 42 }}
        threshold={80}
        holdHours={48}
        changing
        busy={false}
        onUse={() => {}}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("we'll look at it before paying you");
    expect(screen.getByRole("status")).toHaveTextContent("holds your next payout for 48 hours");
  });

  it("shows the 48-hour hold after a change, with who to tell", () => {
    render(<InUse upi={data.detailsHeld.inUse!} />);
    expect(
      screen.getByText(/Your payout UPI ID was changed\. No payout goes to it before/),
    ).toHaveTextContent("Not you? Contact us");
  });
});

describe("the payouts' words", () => {
  it("has words for every status RazorpayX gives, as the control plane counts them", () => {
    const metrics = readFileSync(
      "../control-plane/src/main/java/com/idleo/controlplane/payouts/internal/PayoutsMetrics.java",
      "utf8",
    );
    const listed = /STATUSES = Set\.of\(([^)]*)\)/
      .exec(metrics)![1]!
      .match(/"([a-z]+)"/g)!
      .map((s) => s.replaceAll('"', ""));
    expect(listed.length).toBeGreaterThan(5);
    for (const status of listed) {
      expect(PAYOUT_STATUS[status], status).toBeDefined();
    }
  });

  it("knows a PAN's and a UPI ID's shape as the server does", () => {
    expect(PAN_SHAPE.test("ABCPE1234F")).toBe(true);
    expect(PAN_SHAPE.test("ABCGE1234F")).toBe(false);
    expect(PAN_SHAPE.test("ABCPE1234")).toBe(false);
    expect(UPI_SHAPE.test("asha.rao@okicici")).toBe(true);
    expect(UPI_SHAPE.test("asha rao@okicici")).toBe(false);
  });

  it("derives the fixtures' figures from their payouts", () => {
    for (const payout of data.payouts) {
      expect(payout.netPaise).toBe(payout.grossPaise - payout.tdsPaise - payout.feePaise);
    }
    expect(data.summary.pendingPaise).toBe(
      data.pendingSessions.reduce((total, session) => total + session.amountPaise, 0),
    );
  });
});
