import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { StateChip } from "@/components/ui/StateChip";
import { CreditCard } from "@/features/home/CreditCard";
import { RecentCard, RunningCard } from "@/features/home/SessionsCard";
import { data, queryResult, session, wallet } from "@/test/fixtures";

vi.mock("@/api/queries", async (original) => ({
  ...(await original<typeof import("@/api/queries")>()),
  useTemplateName: () => (id: string) => data.templateList.find((template) => template.id === id)?.name ?? id,
}));

describe("the credit card", () => {
  it("shows available, on hold and total, exactly", () => {
    render(<CreditCard wallet={queryResult({ data: wallet })} />);
    expect(screen.getByText("₹462.12")).toBeInTheDocument();
    expect(screen.getByText("₹5.40")).toBeInTheDocument();
    expect(screen.getByText("₹467.52")).toBeInTheDocument();
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

  it("keeps stale figures, leaving the one stale notice to the screen", () => {
    render(<CreditCard wallet={queryResult({ data: wallet, isRefetchError: true })} />);
    expect(screen.queryByText(/Couldn't refresh/)).not.toBeInTheDocument();
    expect(screen.getByText("₹462.12")).toBeInTheDocument();
  });
});

describe("the sessions cards", () => {
  it("shows a running session with its cost so far, live, and its whole name", () => {
    const live = session({ id: "a", chargedPaise: 2, accruedPaise: 4 });
    render(<RunningCard sessions={[live]} updatedAt={data.NOW.getTime()} stale={false} />);
    const running = screen.getByRole("region", { name: "Running now" });
    expect(within(running).getByText("Running")).toBeInTheDocument();
    expect(within(running).getByText("₹0.06")).toBeInTheDocument();
    expect(within(running).getByText(/Live · updated/)).toBeInTheDocument();
    expect(
      within(running).getByText("QLoRA fine-tune of a small language model (Qwen 2.5 1.5B, 4-bit)"),
    ).not.toHaveClass("truncate");
  });

  it("never says live while the figures are stale", () => {
    render(<RunningCard sessions={[session()]} updatedAt={data.NOW.getTime()} stale />);
    expect(screen.queryByText(/Live/)).not.toBeInTheDocument();
  });

  it("lists ended sessions with their final charge, each one link", () => {
    const done = session({ id: "b", state: "COMPLETED", chargedPaise: 6, accruedPaise: 0, settled: true });
    render(<RecentCard sessions={queryResult({ data: { sessions: [done, session()] } })} />);
    const recent = screen.getByRole("region", { name: "Recent sessions" });
    expect(within(recent).getByText("Completed")).toBeInTheDocument();
    expect(within(recent).getByText("₹0.06")).toBeInTheDocument();
    expect(within(recent).getAllByRole("link", { name: /QLoRA/ })).toHaveLength(1);
    expect(within(recent).getByRole("link", { name: /QLoRA/ })).toHaveAttribute("href", "/session/?id=b");
  });

  it("says why it's empty and what to do", () => {
    render(<RecentCard sessions={queryResult({ data: { sessions: [] } })} />);
    expect(screen.getByText("No sessions yet")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Book a GPU" })).toHaveLength(1);
  });

  it("has loading and error states", () => {
    const { rerender } = render(<RecentCard sessions={queryResult({ isPending: true })} />);
    expect(screen.getByLabelText("Loading your sessions")).toBeInTheDocument();

    rerender(<RecentCard sessions={queryResult({ isError: true, error: new TypeError("offline") })} />);
    expect(screen.getByText(/Check your connection/)).toBeInTheDocument();
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
