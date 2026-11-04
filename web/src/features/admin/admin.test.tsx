import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import { ActionSheet, whatChanged } from "./ActionSheet";
import { CodeField } from "./CodeField";
import { isTotp } from "./rules";

function Sheet({
  onConfirm,
  typed,
  record,
}: {
  onConfirm: (reason: string) => Promise<unknown>;
  typed?: string;
  record?: { version: number; status: string };
}) {
  const [open, setOpen] = useState(true);
  return (
    <QueryClientProvider client={new QueryClient()}>
      <ActionSheet
        open={open}
        onOpenChange={setOpen}
        title="Suspend ravi@example.com?"
        change="Their sign-ins and API keys are refused."
        confirm="Suspend"
        destructive
        {...(typed === undefined ? {} : { typed })}
        {...(record === undefined ? {} : { record })}
        onConfirm={onConfirm}
      />
    </QueryClientProvider>
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

describe("the action sheet's safe writes", () => {
  it("asks for the target's short name before something that can't be undone", async () => {
    const onConfirm = vi.fn(() => Promise.resolve());
    render(<Sheet onConfirm={onConfirm} typed="01a1a7bf" />);
    await userEvent.type(screen.getByLabelText("Reason"), "card testing");
    const confirm = screen.getByRole("button", { name: "Suspend" });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/to confirm$/), "01a1a7b");
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/to confirm$/), "f");
    await userEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledWith("card testing");
  });

  it("says so when the record changed, and stays open to decide again", async () => {
    const onConfirm = vi.fn(() =>
      Promise.reject(new ApiError(412, "/problems/version-stale", "Stale", "Changed")),
    );
    render(<Sheet onConfirm={onConfirm} record={{ version: 2, status: "active" }} />);
    await userEvent.type(screen.getByLabelText("Reason"), "card testing");
    await userEvent.click(screen.getByRole("button", { name: "Suspend" }));
    expect(await screen.findByText(/This changed since you opened it/)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Suspend" })).toBeEnabled();
  });

  it("names each field that changed, leaving out the version and whole objects", () => {
    expect(
      whatChanged(
        { version: 1, status: "active", name: "Ravi", detail: { a: 1 } },
        { version: 2, status: "suspended", name: "Ravi", detail: { a: 2 } },
      ),
    ).toEqual(["status: active → suspended"]);
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
