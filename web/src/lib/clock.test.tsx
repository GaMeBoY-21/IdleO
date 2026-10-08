import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { runningTimers } from "./clock";

describe("the app's one clock", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-07T06:34:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("moves every relative time on screen together every 30 seconds, with one timer", () => {
    render(
      <>
        <RelativeTime value="2026-10-07T06:15:40Z" prefix="Started" />
        <RelativeTime value="2026-10-07T06:33:20Z" />
        <RelativeTime value="2026-10-07T04:34:00Z" />
      </>,
    );
    expect(screen.getByText("Started 18 min ago")).toBeInTheDocument();
    expect(screen.getByText("just now")).toBeInTheDocument();
    expect(runningTimers()).toBe(1);

    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(screen.getByText("Started 18 min ago")).toBeInTheDocument();
    expect(screen.getByText("1 min ago")).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(screen.getByText("Started 19 min ago")).toBeInTheDocument();
    expect(screen.getByText("2 h ago")).toBeInTheDocument();
  });

  it("ticks elapsed times every second, still on one timer", () => {
    render(
      <>
        <Elapsed from="2026-10-07T06:16:00Z" />
        <Elapsed from="2026-10-07T06:33:50Z" />
        <RelativeTime value="2026-10-07T06:00:00Z" />
      </>,
    );
    expect(screen.getByText("18:00")).toBeInTheDocument();
    expect(screen.getByText("0:10")).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(screen.getByText("18:01")).toBeInTheDocument();
    expect(screen.getByText("0:11")).toBeInTheDocument();
    expect(runningTimers()).toBe(1);
  });

  it("gives the exact time in the element's datetime and title", () => {
    render(<RelativeTime value="2026-10-07T06:15:40Z" prefix="Started" />);
    const time = screen.getByText("Started 18 min ago");
    expect(time.tagName).toBe("TIME");
    expect(time).toHaveAttribute("datetime", "2026-10-07T06:15:40Z");
    expect(time.getAttribute("title")).toMatch(/7 Oct 2026/);
  });

  it("stops an elapsed time at its end, and stops the timer when nothing reads it", () => {
    const { unmount } = render(<Elapsed from="2026-10-07T06:00:00Z" to="2026-10-07T06:02:30Z" />);
    expect(screen.getByText("2:30")).toBeInTheDocument();
    expect(runningTimers()).toBe(0);
    unmount();
    render(<Elapsed from="2026-10-07T06:00:00Z" />).unmount();
    expect(runningTimers()).toBe(0);
  });
});
