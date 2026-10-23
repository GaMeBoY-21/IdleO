import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { StateChip } from "@/components/ui/StateChip";
import { CreditCard } from "@/features/credit/CreditCard";
import { data, queryResult, wallet } from "@/test/fixtures";

vi.mock("@/api/queries", async (original) => ({
  ...(await original<typeof import("@/api/queries")>()),
  useTemplateName: () => (id: string) => data.templateList.find((template) => template.id === id)?.name ?? id,
}));

describe("the credit card", () => {
  it("shows available, on hold, total, bought and free, exactly", () => {
    render(<CreditCard wallet={queryResult({ data: wallet })} />);
    expect(screen.getByText("₹462.12")).toBeInTheDocument();
    expect(screen.getByText("₹5.40")).toBeInTheDocument();
    // The fixture's credit is all free, so the total and the free credit are the same figure.
    expect(screen.getAllByText("₹467.52")).toHaveLength(2);
    expect(screen.getByText("₹0.00")).toBeInTheDocument();
    expect(screen.getByText("Never expires.")).toBeInTheDocument();
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

describe("the shared components", () => {
  it("name a session's state in words, whatever its colour", () => {
    render(
      <>
        <StateChip state="HOST_LOST" />
        <StateChip state="SOMETHING_NEW" />
      </>,
    );
    expect(screen.getByText("Moving to another GPU")).toBeInTheDocument();
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
