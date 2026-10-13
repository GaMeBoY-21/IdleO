import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import { ActionSheet } from "./ActionSheet";
import { CodeField } from "./CodeField";
import { isTotp } from "./rules";

function Sheet({ onConfirm }: { onConfirm: (reason: string) => Promise<unknown> }) {
  const [open, setOpen] = useState(true);
  return (
    <ActionSheet
      open={open}
      onOpenChange={setOpen}
      title="Suspend ravi@example.com?"
      change="Their sign-ins and API keys are refused."
      confirm="Suspend"
      destructive
      onConfirm={onConfirm}
    />
  );
}

describe("the action sheet", () => {
  it("names what will change, and sends nothing until the reason has 5 characters", async () => {
    const onConfirm = vi.fn(() => Promise.resolve());
    render(<Sheet onConfirm={onConfirm} />);
    expect(screen.getByRole("dialog", { name: "Suspend ravi@example.com?" })).toHaveTextContent(
      "Their sign-ins and API keys are refused.",
    );
    await userEvent.type(screen.getByLabelText("Reason"), "spam");
    await userEvent.click(screen.getByRole("button", { name: "Suspend" }));
    expect(screen.getByText(/at least 5 characters/)).toBeInTheDocument();
    expect(screen.getByLabelText("Reason")).toHaveAttribute("aria-invalid", "true");
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText("Reason"), " from this account  ");
    await userEvent.click(screen.getByRole("button", { name: "Suspend" }));
    expect(onConfirm).toHaveBeenCalledWith("spam from this account");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("stays open with the server's reason when the action is refused", async () => {
    const onConfirm = vi.fn(() =>
      Promise.reject(
        new ApiError(409, "/problems/action-refused", "Action refused", "host 01a1 has no listing"),
      ),
    );
    render(<Sheet onConfirm={onConfirm} />);
    await userEvent.type(screen.getByLabelText("Reason"), "passed the canary job");
    await userEvent.click(screen.getByRole("button", { name: "Suspend" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That was refused, and nothing changed. The reason is shown below. host 01a1 has no listing",
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});

describe("the code field", () => {
  function Field() {
    const [value, setValue] = useState("");
    return (
      <CodeField
        kind="totp"
        value={value}
        onChange={setValue}
        error={value && !isTotp(value) ? "6 digits" : null}
      />
    );
  }

  it("asks the phone for its one-time-code keyboard and says what it takes", async () => {
    render(<Field />);
    const field = screen.getByLabelText("Code from your authenticator app");
    expect(field).toHaveAttribute("inputmode", "numeric");
    expect(field).toHaveAttribute("autocomplete", "one-time-code");
    expect(screen.getByText("It changes every 30 seconds.")).toBeInTheDocument();
    await userEvent.type(field, "12a");
    expect(field).toHaveAttribute("aria-invalid", "true");
    await userEvent.clear(field);
    await userEvent.type(field, "123 456");
    expect(field).not.toHaveAttribute("aria-invalid");
  });
});
