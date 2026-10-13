import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import type { Dispute, SessionRating } from "@/api/trust";
import { Rating } from "./Rating";
import { ReportRow } from "./ReportRow";
import { ReportSheet } from "./ReportSheet";

const SESSION = "01a1a000-0000-7000-8000-00000000a001";
const ENDED = new Date().toISOString();

const rating = vi.hoisted(() => ({
  data: undefined as SessionRating | undefined,
  error: null as ApiError | null,
  isError: false,
  isFetching: false,
  refetch: vi.fn(),
}));
const rate = vi.hoisted(() => ({
  mutate: vi.fn(),
  isPending: false,
  isError: false,
  error: null as ApiError | null,
}));
const reports = vi.hoisted(() => ({
  data: undefined as { disputes: Dispute[] } | undefined,
  isPending: false,
  isError: false,
}));
const report = vi.hoisted(() => ({
  mutate: vi.fn(),
  reset: vi.fn(),
  isPending: false,
  isSuccess: false,
  isError: false,
  error: null as ApiError | null,
}));

vi.mock("@/api/trust", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/trust")>()),
  useRating: () => rating,
  useRate: () => rate,
  useSessionDisputes: () => reports,
  useReport: () => report,
}));

const unrated = (over: Partial<SessionRating> = {}): SessionRating => ({
  sessionId: SESSION,
  rateable: true,
  rateableUntil: "2026-10-20T10:00:00Z",
  ...over,
});

const dispute = (over: Partial<Dispute>): Dispute => ({
  id: "01a1a000-0000-7000-8000-00000000d001",
  sessionId: SESSION,
  reason: "failed",
  state: "open",
  createdAt: "2026-10-13T10:00:00Z",
  ...over,
});

beforeEach(() => {
  rating.data = unrated();
  rating.isError = false;
  rating.error = null;
  rate.mutate.mockReset();
  rate.isPending = false;
  rate.isError = false;
  reports.data = { disputes: [] };
  reports.isPending = false;
  reports.isError = false;
  report.mutate.mockReset();
  report.reset.mockReset();
  report.isSuccess = false;
  report.isError = false;
  report.isPending = false;
});

describe("rating a session", () => {
  it("rates with one tap, keeping the comment as it was saved", async () => {
    render(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    expect(screen.getByRole("heading", { name: "How did it go?" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "4 stars" }));
    expect(rate.mutate).toHaveBeenCalledWith({ stars: 4, comment: "" }, expect.any(Object));
  });

  it("opens a comment box once the tap is saved, and saves the comment with the same stars", async () => {
    rate.mutate.mockImplementation((_: unknown, options?: { onSuccess: (done: SessionRating) => void }) =>
      options?.onSuccess(unrated({ stars: 4, comment: null })),
    );
    const { rerender } = render(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    await userEvent.click(screen.getByRole("button", { name: "4 stars" }));
    rating.data = unrated({ stars: 4 });
    rerender(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    const box = await screen.findByLabelText("Comment (optional)");
    expect(screen.getByText(/Only we read it/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save comment" })).toBeDisabled();
    await userEvent.type(box, "Fast to start.");
    await userEvent.click(screen.getByRole("button", { name: "Save comment" }));
    expect(rate.mutate).toHaveBeenLastCalledWith({ stars: 4, comment: "Fast to start." });
  });

  it("shows a rating already given, changeable until its day, with the stars pressed up to it", () => {
    rating.data = unrated({ stars: 3, comment: "Okay." });
    render(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    expect(screen.getByRole("heading", { name: "Thanks for rating" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "You gave 3 out of 5 stars" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "3 stars" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText(/You can change it until/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit your comment" })).toBeInTheDocument();
  });

  it("is read-only once it has closed, and says so", () => {
    rating.data = unrated({ rateable: false, stars: 5, comment: "Great." });
    render(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    expect(screen.getByRole("heading", { name: "You rated this session" })).toBeInTheDocument();
    for (const star of screen.getAllByRole("button", { name: /star/ })) {
      expect(star).toBeDisabled();
    }
    expect(screen.getByText(/It closed on/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /comment/i })).not.toBeInTheDocument();
  });

  it("holds its stars back while the rating loads, and while the control plane hasn't noted the end yet", () => {
    rating.data = undefined;
    const { rerender } = render(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    for (const star of screen.getAllByRole("button", { name: /star/ })) {
      expect(star).toBeDisabled();
    }
    rating.isError = true;
    rating.error = ApiError.of(404, { type: "/problems/not-found", status: 404 });
    rerender(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    expect(screen.getByText("One moment: we're noting that it ended.")).toBeInTheDocument();
    // A session that ended long before ratings existed is never noted.
    rerender(<Rating sessionId={SESSION} endedAt="2026-01-01T00:00:00Z" enabled />);
    expect(screen.getByText("This session can't be rated.")).toBeInTheDocument();
  });

  it("says what the server refused, in the problem catalog's words", () => {
    rate.isError = true;
    rate.error = ApiError.of(409, { type: "/problems/rating-closed", status: 409 });
    render(<Rating sessionId={SESSION} endedAt={ENDED} enabled />);
    expect(screen.getByRole("alert")).toHaveTextContent(/too late to rate/);
  });
});

describe("the report row on a session", () => {
  it("offers to report a problem when there's none, and opens the sheet", async () => {
    render(<ReportRow sessionId={SESSION} enabled />);
    expect(screen.getByRole("button", { name: /Report a problem/ })).toBeEnabled();
    await userEvent.click(screen.getByRole("button", { name: /Report a problem/ }));
    expect(await screen.findByRole("dialog", { name: "Report a problem" })).toBeInTheDocument();
  });

  it("holds the button back while it checks for an earlier report", () => {
    reports.data = undefined;
    reports.isPending = true;
    render(<ReportRow sessionId={SESSION} enabled />);
    expect(screen.getByRole("button", { name: /Report a problem/ })).toBeDisabled();
  });

  it("says an open report will be answered within 2 days, and links to Reports", () => {
    reports.data = { disputes: [dispute({})] };
    render(<ReportRow sessionId={SESSION} enabled />);
    const row = screen.getByRole("link");
    expect(row).toHaveAttribute("href", "/account/reports/");
    expect(row).toHaveTextContent("Your report is open");
    expect(row).toHaveTextContent("We'll reply within 2 days.");
  });

  it("says what a resolved one decided, with the refund from integer paise", () => {
    reports.data = {
      disputes: [dispute({ state: "refunded", refundPaise: 4_000, resolutionReason: "Our host failed." })],
    };
    render(<ReportRow sessionId={SESSION} enabled />);
    expect(screen.getByRole("link")).toHaveTextContent("Report resolved: ₹40.00 refunded");
    expect(screen.getByRole("link")).toHaveTextContent("Our host failed.");
  });
});

describe("the report sheet", () => {
  const open = () => render(<ReportSheet sessionId={SESSION} open onOpenChange={() => undefined} />);

  it("lists the six reasons as options, and sends nothing until one is chosen", async () => {
    open();
    const dialog = screen.getByRole("dialog", { name: "Report a problem" });
    const options = within(screen.getByRole("group", { name: "What went wrong" })).getAllByRole("button");
    expect(options).toHaveLength(6);
    expect(within(dialog).getByRole("button", { name: "Send report" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: /It was slow/ }));
    expect(within(dialog).getByRole("button", { name: "Send report" })).toBeEnabled();
  });

  it("sends the reason and what was written, the same Idempotency-Key for the same form", async () => {
    open();
    await userEvent.click(screen.getByRole("button", { name: /The output was wrong/ }));
    await userEvent.type(screen.getByLabelText("Tell us more (optional)"), "  The adapter is empty.  ");
    await userEvent.click(screen.getByRole("button", { name: "Send report" }));
    await userEvent.click(screen.getByRole("button", { name: "Send report" }));
    expect(report.mutate).toHaveBeenCalledTimes(2);
    const [first, second] = report.mutate.mock.calls.map(
      (call) => call[0] as { reason: string; details: string; idempotencyKey: string },
    );
    expect(first).toMatchObject({
      sessionId: SESSION,
      reason: "wrong_output",
      details: "The adapter is empty.",
    });
    expect(second?.idempotencyKey).toBe(first?.idempotencyKey);
    // Another form is another report.
    await userEvent.click(screen.getByRole("button", { name: /It failed/ }));
    await userEvent.click(screen.getByRole("button", { name: "Send report" }));
    const third = report.mutate.mock.calls[2]?.[0] as { idempotencyKey: string };
    expect(third.idempotencyKey).not.toBe(first?.idempotencyKey);
  });

  it("says we'll reply within 2 days once it's sent", () => {
    report.isSuccess = true;
    open();
    expect(screen.getByRole("dialog", { name: "Report sent" })).toHaveTextContent(
      "We'll reply within 2 days.",
    );
  });

  it("says why a report was refused", () => {
    report.isError = true;
    report.error = ApiError.of(409, { type: "/problems/report-window-closed", status: 409 });
    open();
    expect(screen.getByRole("alert")).toHaveTextContent(/within 7 days/);
  });
});
