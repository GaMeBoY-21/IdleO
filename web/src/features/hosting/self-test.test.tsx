import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as data from "../../../e2e/fixtures-hosting";
import { ApiError } from "@/api/errors";
import { reliabilityLowers, reliabilityWord, SELF_TEST_CHECKS } from "@/lib/hosting";
import { ReliabilityLine } from "./MachineFigures";
import { SelfTestPanel, selfTestRows } from "./SelfTestPanel";

const mocks = vi.hoisted(() => {
  const status: {
    data: unknown;
    isError: boolean;
    isFetching: boolean;
    isRefetchError: boolean;
    dataUpdatedAt: number;
    error: unknown;
    refetch: ReturnType<typeof vi.fn>;
  } = {
    data: undefined,
    isError: false,
    isFetching: false,
    isRefetchError: false,
    dataUpdatedAt: 0,
    error: null,
    refetch: vi.fn(),
  };
  const start: { mutate: ReturnType<typeof vi.fn>; isPending: boolean; error: unknown } = {
    mutate: vi.fn(),
    isPending: false,
    error: null,
  };
  return { status, start };
});

vi.mock("@/api/hosting", () => ({
  useSelfTest: () => mocks.status,
  useRunSelfTest: () => mocks.start,
}));

const now = new Date("2026-10-07T06:34:00Z").getTime();

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(now);
  Object.assign(mocks.status, { data: undefined, isError: false, isRefetchError: false, error: null });
  Object.assign(mocks.start, { isPending: false, error: null });
  mocks.start.mutate.mockClear();
});
afterEach(() => vi.useRealTimers());

const panel = (status: unknown) => {
  mocks.status.data = status;
  return render(<SelfTestPanel host={data.hostIdle} />);
};

describe("Reliability on the machine page", () => {
  it("says Good, Fair or New, and nothing is holding a good machine down", () => {
    const { rerender } = render(<ReliabilityLine host={data.hostIdle} />);
    expect(screen.getByText(/^Reliability:/)).toHaveTextContent("Reliability: Good");
    expect(screen.queryByRole("list", { name: "What lowers it" })).toBeNull();
    rerender(<ReliabilityLine host={data.hostNew} />);
    expect(screen.getByText(/^Reliability:/)).toHaveTextContent("Reliability: New");
  });

  it("names what lowers it in plain sentences, with counts, and never says who reported", () => {
    render(<ReliabilityLine host={data.hostOffline} />);
    expect(screen.getByText(/^Reliability:/)).toHaveTextContent("Reliability: Fair");
    const lowers = within(screen.getByRole("list", { name: "What lowers it" })).getAllByRole("listitem");
    expect(lowers.map((item) => item.textContent)).toEqual([
      "2 sessions were lost here in the last 30 days (the app went offline or stopped).",
      "1 self-test in the last 30 days had a check that failed.",
      "Renters' reports about sessions here counted against it.",
    ]);
  });

  it("has a word for every label and a sentence for every kind of thing that lowers it", () => {
    expect(["good", "fair", "new"].map((label) => reliabilityWord(label as "good"))).toEqual([
      "Good",
      "Fair",
      "New",
    ]);
    expect(reliabilityLowers([{ kind: "sessions_lost", count: 1 }])).toEqual([
      "1 session was lost here in the last 30 days (the app went offline or stopped).",
    ]);
  });
});

describe("Run self-test", () => {
  it("holds its place while it asks, and offers to ask again if it can't", async () => {
    const { unmount } = render(<SelfTestPanel host={data.hostIdle} />);
    expect(screen.getByRole("status", { name: "Loading the self-test" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Run self-test" })).toBeNull();
    unmount();
    Object.assign(mocks.status, { isError: true, error: new ApiError(503, null, null, null) });
    render(<SelfTestPanel host={data.hostIdle} />);
    await userEvent.click(screen.getByRole("button", { name: /Try again/ }));
    expect(mocks.status.refetch).toHaveBeenCalled();
  });

  it("offers the run with how many a day, and starts it when pressed", async () => {
    panel(data.selfTestNever);
    expect(screen.getByText("Not run from here yet.")).toBeInTheDocument();
    expect(screen.getByText("Up to 6 a day.")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Checks" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Run self-test" }));
    expect(mocks.start.mutate).toHaveBeenCalledTimes(1);
  });

  it("shows every check from the start, each waiting, and ticks them off as they arrive", () => {
    const { unmount } = panel(data.selfTestStarted);
    let items = within(screen.getByRole("list", { name: "Checks" })).getAllByRole("listitem");
    expect(items).toHaveLength(SELF_TEST_CHECKS.length);
    expect(items.every((item) => item.textContent.includes("waiting"))).toBe(true);
    expect(screen.getByText("Running · 0 of 11 checks done")).toBeInTheDocument();
    unmount();

    panel(data.selfTestHalfWay);
    items = within(screen.getByRole("list", { name: "Checks" })).getAllByRole("listitem");
    expect(items).toHaveLength(SELF_TEST_CHECKS.length);
    expect(items.filter((item) => item.textContent.includes("passed"))).toHaveLength(5);
    expect(items.filter((item) => item.textContent.includes("waiting"))).toHaveLength(6);
    expect(screen.getByText("Running · 5 of 11 checks done")).toBeInTheDocument();
    expect(items[0]).toHaveTextContent("IdleO's Linux is installed");
    expect(items[0]).toHaveTextContent("distro_registered found nothing wrong");
  });

  it("can't be pressed while one runs, and says why", () => {
    panel(data.selfTestHalfWay);
    expect(screen.getByRole("button", { name: "Run self-test" })).toBeDisabled();
    expect(screen.getByText(/already running on this machine/)).toBeInTheDocument();
  });

  it("says why it can't run when the machine is offline, busy or out of runs for the day", () => {
    const { unmount } = panel(data.selfTestOffline);
    expect(screen.getByRole("button", { name: "Run self-test" })).toBeDisabled();
    expect(
      screen.getByText("This machine isn't connected right now. Open IdleO on it, then try again."),
    ).toBeVisible();
    unmount();
    const busy = panel(data.selfTestBusy);
    expect(screen.getByText(/A renter's session is running on this machine/)).toBeVisible();
    busy.unmount();
    panel(data.selfTestLimit);
    expect(screen.getByRole("button", { name: "Run self-test" })).toBeDisabled();
    expect(screen.getByText(/6 times in the last 24 hours/)).toBeVisible();
  });

  it("shows a passed run, a failed one with what the failing check found, and an unfinished one", () => {
    const passed = panel(data.selfTestPassed);
    expect(screen.getByText(/^Passed/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Run self-test" })).toBeEnabled();
    passed.unmount();

    const failed = panel(data.selfTestFailed);
    expect(screen.getByText(/^A check failed/)).toBeInTheDocument();
    const lockdown = screen.getByText("Jobs can't reach your computer").closest("li")!;
    expect(lockdown).toHaveTextContent("lockdown failed: it did not answer");
    expect(lockdown).toHaveTextContent("failed");
    failed.unmount();

    panel(data.selfTestTimedOut);
    expect(screen.getByText(/The app didn't answer in time/)).toBeInTheDocument();
    const items = within(screen.getByRole("list", { name: "Checks" })).getAllByRole("listitem");
    expect(items.filter((item) => item.textContent.includes("not run"))).toHaveLength(9);
  });

  it("says in words when the app wouldn't run it", () => {
    panel(data.selfTestRefused);
    expect(
      screen.getByText(/^A renter's session started on this machine, so the app didn't run it\./),
    ).toBeInTheDocument();
  });

  it("tells the person when pressing it was refused", () => {
    mocks.start.error = new ApiError(409, "/problems/host-offline", "x", "y");
    panel(data.selfTestNever);
    expect(
      screen.getByText("This machine isn't connected right now. Open IdleO on it, then try again."),
    ).toBeVisible();
  });

  it("lists a check it has no words for after the known ones, so nothing the app reports is lost", () => {
    const rows = selfTestRows({
      ...data.selfTestPassed.run,
      checks: [
        ...data.selfTestPassed.run.checks,
        { name: "new_check", passed: true, detail: "ok", tookMs: 5 },
      ],
    });
    expect(rows).toHaveLength(SELF_TEST_CHECKS.length + 1);
    expect(rows.at(-1)).toMatchObject({ name: "new_check", label: "new_check" });
  });
});
