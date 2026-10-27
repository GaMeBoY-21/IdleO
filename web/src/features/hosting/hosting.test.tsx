import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as data from "../../../e2e/fixtures-hosting";
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
    expect(row.className).toContain("bg-night");
  });

  it("shows a paused machine in grey, until when", () => {
    render(<MachineRow host={data.hostPaused} />);
    const row = screen.getByRole("link");
    expect(row).toHaveTextContent("Home desktopPaused until 6 pm");
    expect(row.className).toContain("bg-panel");
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
