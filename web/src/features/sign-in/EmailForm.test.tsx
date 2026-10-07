import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { EmailForm } from "./EmailForm";

describe("the email form", () => {
  const renderForm = () => {
    const onSubmit = vi.fn(async () => undefined);
    render(<EmailForm label="Email" submit="Email me a sign-in link" onSubmit={onSubmit} />);
    return onSubmit;
  };

  it("asks for an address before sending anything", async () => {
    const onSubmit = renderForm();
    await userEvent.click(screen.getByRole("button", { name: "Email me a sign-in link" }));
    expect(await screen.findByText("Enter your email address.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("refuses something that isn't an address", async () => {
    const onSubmit = renderForm();
    await userEvent.type(screen.getByLabelText("Email"), "asha");
    await userEvent.click(screen.getByRole("button", { name: "Email me a sign-in link" }));
    expect(await screen.findByText("That doesn't look like an email address.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("sends the address, trimmed", async () => {
    const onSubmit = renderForm();
    await userEvent.type(screen.getByLabelText("Email"), "  asha@example.com ");
    await userEvent.click(screen.getByRole("button", { name: "Email me a sign-in link" }));
    expect(onSubmit).toHaveBeenCalledWith({ email: "asha@example.com" }, expect.anything());
  });
});
