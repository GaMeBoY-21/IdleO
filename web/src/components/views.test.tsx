import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { StateChip } from "@/components/ui/StateChip";
import { CreditCard } from "@/features/home/CreditCard";
import { SessionsCard } from "@/features/home/SessionsCard";
import { queryResult, session, wallet } from "@/test/fixtures";

vi.mock("@/api/queries", async (original) => ({
  ...(await original<typeof import("@/api/queries")>()),
  useTemplateName: () => (id: string) => (id === "cuda-smoke-test" ? "CUDA smoke test" : id),
}));

describe("the credit card", () => {
  it("shows available, on hold and total, exactly", () => {
    render(<CreditCard wallet={queryResult({ data: wallet })} />);
    expect(screen.getByText("₹499.40")).toBeInTheDocument();
    expect(screen.getByText("₹0.60")).toBeInTheDocument();
    expect(screen.getByText("₹500.00")).toBeInTheDocument();
    expect(screen.getByText("What you can spend now.")).toBeInTheDocument();
  });

  it("shows a skeleton of the same shape while loading", () => {
    render(<CreditCard wallet={queryResult({ isPending: true })} />);
    expect(screen.getByLabelText("Loading your credit")).toBeInTheDocument();
    expect(screen.queryByText(/₹/)).not.toBeInTheDocument();
  });

  it("says what went wrong and offers to try again", async () => {
    const refetch = vi.fn(async () => ({}) as never);
    render(
      <CreditCard
        wallet={queryResult({ isError: true, error: new ApiError(503, null, null, null), refetch })}
      />,
    );
    expect(screen.getByText(/problem on our side/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(refetch).toHaveBeenCalled();
  });

  it("says when restored figures are from while it refreshes them", () => {
    render(
      <CreditCard
        wallet={queryResult({ data: wallet, isFetching: true, dataUpdatedAt: Date.now() - 24 * 60 * 60_000 })}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(/Updated .* · refreshing/);
  });

  it("keeps stale figures, and says they're stale", () => {
    render(<CreditCard wallet={queryResult({ data: wallet, isRefetchError: true })} />);
    expect(screen.getByRole("status")).toHaveTextContent(
      /Couldn't refresh. Showing figures from .* Retrying/,
    );
    expect(screen.getByText("₹499.40")).toBeInTheDocument();
  });
});

describe("the sessions card", () => {
  it("lists live sessions first with their cost so far, then recent ones with their charge", () => {
    const live = session({ id: "a", chargedPaise: 2, accruedPaise: 4 });
    const done = session({ id: "b", state: "COMPLETED", chargedPaise: 6, accruedPaise: 0 });
    render(<SessionsCard sessions={queryResult({ data: { sessions: [done, live] } })} />);

    const running = screen.getByRole("region", { name: "Running now" });
    expect(within(running).getByText("Running")).toBeInTheDocument();
    expect(within(running).getByText("₹0.06")).toBeInTheDocument();
    expect(
      within(running).getByText("so far", { exact: false, selector: "span.text-text-muted" }),
    ).toBeInTheDocument();
    const recent = screen.getByRole("region", { name: "Recent" });
    expect(within(recent).getByText("Completed")).toBeInTheDocument();
    expect(within(recent).getByText("₹0.06")).toBeInTheDocument();
    expect(within(recent).getByRole("link")).toHaveAttribute("href", "/session/?id=b");
  });

  it("says why it's empty and what to do", () => {
    render(<SessionsCard sessions={queryResult({ data: { sessions: [] } })} />);
    expect(screen.getByText("No sessions yet")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Book a GPU" })).toHaveLength(1);
  });

  it("has loading, error and stale states", async () => {
    const { rerender } = render(<SessionsCard sessions={queryResult({ isPending: true })} />);
    expect(screen.getByLabelText("Loading your sessions")).toBeInTheDocument();

    rerender(<SessionsCard sessions={queryResult({ isError: true, error: new TypeError("offline") })} />);
    expect(screen.getByText(/Check your connection/)).toBeInTheDocument();

    rerender(
      <SessionsCard sessions={queryResult({ data: { sessions: [session()] }, isRefetchError: true })} />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(/Couldn't refresh/);
  });
});

describe("the shared components", () => {
  it("name a session's state in words, whatever its colour", () => {
    render(
      <>
        <StateChip state="HOST_LOST" />
        <StateChip state="SOMETHING_NEW" />
      </>,
    );
    expect(screen.getByText("Moving to another host")).toBeInTheDocument();
    expect(screen.getByText("Something new")).toBeInTheDocument();
  });

  it("disable a busy button without changing its label", () => {
    render(<Button busy>Email me a sign-in link</Button>);
    const button = screen.getByRole("button", { name: "Email me a sign-in link" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
  });

  it("tie a field's error to its input", () => {
    render(<Field label="Email" error="Enter your email address." />);
    const input = screen.getByLabelText("Email");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("Enter your email address.");
  });
});
