import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as data from "../../../e2e/fixtures-hosting";
import { EnrolmentCodeCard } from "./EnrolmentCode";
import { MachineChip } from "./MachineChip";
import { RightNow } from "./MachineFigures";
import { MachineRow } from "./MachineRow";
import { WeekBars } from "./WeekBars";
import { machineState } from "@/lib/hosting";

const now = new Date("2026-10-07T06:34:00Z").getTime();

// The fixtures are timed against one fixed moment; the app's clock reads Date.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(now);
});
afterEach(() => vi.useRealTimers());

describe("the Hosting tab's machines", () => {
  it("shows a running machine as a dark tile that opens it, with what it has earned so far", () => {
    render(<MachineRow host={data.hostRunning} />);
    const row = screen.getByRole("link");
    expect(row).toHaveAttribute("href", `/hosting/machine/?id=${data.ROG.id}`);
    expect(row).toHaveTextContent(`${data.ROG.name}Running a session · earning ₹`);
    expect(row.className).toContain("bg-dark");
  });

  it("shows a paused machine in grey, until when", () => {
    render(<MachineRow host={data.hostPaused} />);
    const row = screen.getByRole("link");
    expect(row).toHaveTextContent("Home desktopPaused until 6 pm");
    expect(row.className).toContain("bg-fill");
  });

  it("says in words when a machine is offline, or needs an update", () => {
    const { rerender } = render(<MachineRow host={data.hostOffline} />);
    expect(screen.getByRole("link")).toHaveTextContent("Offline · RTX 4080 Laptop · 12 GB");
    rerender(<MachineRow host={data.hostUpdate} />);
    expect(screen.getByRole("link")).toHaveTextContent("Update the host app to get work");
  });

  it("names its state in the chip, whatever its colour", () => {
    render(<MachineChip state={machineState(data.hostRunning, now)} />);
    expect(screen.getByTestId("machine-chip")).toHaveTextContent("Running a session");
  });
});

describe("the week's bars", () => {
  it("name each day and what it earned for a screen reader, with today last", () => {
    render(<WeekBars days={data.earnings.days} />);
    const days = within(screen.getByRole("list", { name: "Earned each day, the last 7 days" })).getAllByRole(
      "listitem",
    );
    expect(days).toHaveLength(7);
    expect(days.at(-1)).toHaveTextContent(/^Wednesday, ₹\d/);
    expect(days[0]).toHaveTextContent(/^Thursday, /);
  });
});

describe("Right now", () => {
  it("shows what's running and what the machine has earned from it so far, and the 80% it keeps", () => {
    render(<RightNow host={data.hostRunning} />);
    expect(
      screen.getByText((_, element) => /^Fine-tuning · \d+:\d\d$/.test(element?.textContent ?? "")),
    ).toBeInTheDocument();
    expect(screen.getByText(/earned so far · you keep 80%/)).toBeInTheDocument();
    expect(screen.getByText("₹2.59")).toBeInTheDocument();
  });

  it("says nothing is running, and never invents earnings", () => {
    render(<RightNow host={data.hostIdle} />);
    expect(screen.getByText(/Nothing is running/)).toBeInTheDocument();
    expect(screen.queryByText(/₹/)).not.toBeInTheDocument();
  });
});

describe("the enrolment code", () => {
  it("shows the code, and its countdown to the second", () => {
    const code = {
      ...data.enrolmentCode,
      expiresAt: new Date(Date.now() + 58 * 60_000 + 12_000).toISOString(),
    };
    render(<EnrolmentCodeCard code={code} issuing={false} onNewCode={() => undefined} />);
    expect(screen.getByText("K7Q2M-9XMD4-H8TQ3-R5W2N")).toBeInTheDocument();
    expect(screen.getByText(/^Works once · expires in \d\d:\d\d$/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Install the host app" })).toHaveAttribute("href");
    expect(
      screen.getByText(/The Windows app is coming; for now run the agent from the runbook/),
    ).toBeInTheDocument();
  });

  it("says when it has expired, and leaves Copy code off", () => {
    const expired = { code: "K7Q2M-9XMD4-H8TQ3-R5W2N", expiresAt: "2020-01-01T00:00:00Z" };
    render(<EnrolmentCodeCard code={expired} issuing={false} onNewCode={() => undefined} />);
    expect(screen.getByText("This code has expired. Get a new one.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Copy code/ })).toBeDisabled();
    expect(screen.queryByText("K7Q2M-9XMD4-H8TQ3-R5W2N")).not.toBeInTheDocument();
  });

  it("asks for a code when it has none, as after a reload", () => {
    render(<EnrolmentCodeCard code={null} issuing={false} onNewCode={() => undefined} />);
    expect(screen.getByText("Get a code to continue.")).toBeInTheDocument();
  });
});
