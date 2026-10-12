import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApiKey, CreatedApiKey } from "@/api/keys";
import { CreateKey, keyNameProblem } from "./CreateKey";
import { RevokeDialog } from "./RevokeDialog";

const create = vi.hoisted(() => ({
  mutateAsync: vi.fn(),
  reset: vi.fn(),
  isPending: false,
  isError: false,
  error: null as Error | null,
}));

vi.mock("@/api/keys", () => ({ useCreateKey: () => create }));

const made: CreatedApiKey = {
  id: "01a12ffa-246f-759d-8178-69cc2a07b3ce",
  name: "ci-box",
  prefix: "idleo_k3j9x2mq",
  scope: "full",
  createdAt: "2026-10-12T10:00:00Z",
  key: "idleo_k3j9x2mq" + "a".repeat(44),
};

beforeEach(() => {
  create.mutateAsync.mockReset();
  create.reset.mockReset();
  create.isError = false;
  create.error = null;
});

describe("a key's name", () => {
  it("is one to sixty characters once the spaces around it are gone", () => {
    expect(keyNameProblem("")).toMatch(/Give the key a name/);
    expect(keyNameProblem("   ")).toMatch(/Give the key a name/);
    expect(keyNameProblem("ci")).toBeUndefined();
    expect(keyNameProblem(`  ${"x".repeat(60)}  `)).toBeUndefined();
    expect(keyNameProblem("x".repeat(61))).toBe("Use at most 60 characters.");
  });
});

describe("creating a key", () => {
  it("asks for a name before sending anything", async () => {
    render(<CreateKey />);
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));
    expect(await screen.findByText(/Give the key a name/)).toBeInTheDocument();
    expect(create.mutateAsync).not.toHaveBeenCalled();
  });

  it("sends the name and the access chosen, and the same Idempotency-Key when the form is the same", async () => {
    create.mutateAsync.mockRejectedValueOnce(new Error("lost")).mockResolvedValueOnce(made);
    render(<CreateKey />);
    await userEvent.type(screen.getByLabelText("Name"), "  ci-box ");
    await userEvent.click(screen.getByLabelText("Read only"));
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));

    expect(create.mutateAsync).toHaveBeenCalledTimes(2);
    const [first, second] = create.mutateAsync.mock.calls.map(([call]) => call as Record<string, string>);
    expect(first).toMatchObject({ name: "ci-box", scope: "read" });
    expect(second!.idempotencyKey).toBe(first!.idempotencyKey);
  });

  it("shows the new key once, with a plain warning, and forgets it when done", async () => {
    create.mutateAsync.mockResolvedValue(made);
    render(<CreateKey />);
    await userEvent.type(screen.getByLabelText("Name"), "ci-box");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));

    expect(await screen.findByTestId("new-key")).toHaveTextContent(made.key);
    expect(screen.getByText(/You won't see it again/)).toBeInTheDocument();
    // The mutation's own copy of the answer, the key, isn't kept.
    expect(create.reset).toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByText(made.key)).not.toBeInTheDocument();
    expect(screen.getByRole("form", { name: "Create a key" })).toBeInTheDocument();
  });

  it("copies the key", async () => {
    create.mutateAsync.mockResolvedValue(made);
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(<CreateKey />);
    await userEvent.type(screen.getByLabelText("Name"), "ci-box");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));
    await userEvent.click(await screen.findByRole("button", { name: "Copy key" }));
    expect(writeText).toHaveBeenCalledWith(made.key);
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("says in words when it can't be made, and keeps the form", async () => {
    create.isError = true;
    create.error = new (await import("@/api/errors")).ApiError(
      409,
      "/problems/too-many-api-keys",
      null,
      null,
    );
    render(<CreateKey />);
    expect(screen.getByRole("alert")).toHaveTextContent("You have 20 API keys");
    expect(screen.getByRole("form", { name: "Create a key" })).toBeInTheDocument();
  });
});

describe("revoking a key", () => {
  const key: Pick<ApiKey, "name"> = { name: "ci-box" };

  it("names the key and asks first", async () => {
    const onRevoke = vi.fn();
    render(
      <RevokeDialog
        name={key.name}
        open
        onOpenChange={() => undefined}
        onRevoke={onRevoke}
        revoking={false}
        error={null}
      />,
    );
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Revoke ci-box?")).toBeInTheDocument();
    expect(within(dialog).getByText(/stops working at once/)).toBeInTheDocument();
    expect(onRevoke).not.toHaveBeenCalled();
    await userEvent.click(within(dialog).getByRole("button", { name: "Revoke ci-box" }));
    await waitFor(() => expect(onRevoke).toHaveBeenCalledTimes(1));
  });
});
