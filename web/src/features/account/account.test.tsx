import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as data from "../../../e2e/fixtures";
import { Fact, UNDELIVERABLE } from "./Account";
import { NameRow, nameProblem } from "./NameRow";
import { NotificationChoices } from "./NotificationChoices";

const save = vi.hoisted(() => ({ mutate: vi.fn() }));
const rename = vi.hoisted(() => ({ mutate: vi.fn(), reset: vi.fn() }));

vi.mock("@/api/account", () => ({
  useSaveNotifications: () => ({ ...save, isPending: false, isSuccess: false, isError: false, error: null }),
  useRenameMe: () => ({ ...rename, isPending: false, isError: false, error: null }),
}));

beforeEach(() => {
  save.mutate.mockReset();
  rename.mutate.mockReset();
  rename.reset.mockReset();
});

const LOCKED = [
  "Stopped for credit or cap",
  "Host lost",
  "Machine offline",
  "Payout sent or failed",
  "Top-up confirmed",
  "New device sign-in",
];

describe("the notification choices", () => {
  it("gives every kind a switch for email and one for the app", () => {
    const { items } = data.notificationChoices();
    render(<NotificationChoices items={items} />);
    expect(screen.getAllByRole("switch")).toHaveLength(items.length * 2);
    expect(screen.getByRole("switch", { name: "Session finished: email" })).toBeChecked();
    expect(screen.getByRole("switch", { name: "Session finished: in the app" })).toBeChecked();
  });

  it("locks the money and safety kinds on, each with its reason", () => {
    render(<NotificationChoices items={data.notificationChoices().items} />);
    for (const title of LOCKED) {
      for (const channel of ["email", "in the app"]) {
        const lockedSwitch = screen.getByRole("switch", { name: `${title}: ${channel}` });
        expect(lockedSwitch).toBeChecked();
        expect(lockedSwitch).toBeDisabled();
      }
    }
    expect(screen.getByText("Stopped sessions are about your money.")).toBeInTheDocument();
    expect(screen.getByText("It protects your account.")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Low credit: email" })).toBeEnabled();
  });

  it("never asks the server to turn a locked kind off, whatever is pressed", async () => {
    render(<NotificationChoices items={data.notificationChoices().items} />);
    await userEvent.click(screen.getByRole("switch", { name: "Host lost: email" }));
    expect(save.mutate).not.toHaveBeenCalled();
  });

  it("sends the whole set with the one switch changed", async () => {
    const { items } = data.notificationChoices();
    render(<NotificationChoices items={items} />);
    await userEvent.click(screen.getByRole("switch", { name: "Low credit: email" }));
    expect(save.mutate).toHaveBeenCalledTimes(1);
    const sent = save.mutate.mock.calls[0]![0] as typeof items;
    expect(sent).toHaveLength(items.length);
    expect(sent.find((item) => item.kind === "low-credit")).toMatchObject({ email: false, inApp: true });
    expect(sent.filter((item) => item.kind !== "low-credit").every((item) => item.email && item.inApp)).toBe(
      true,
    );
  });

  it("shows what the server holds, so a switch that wasn't saved stays where it was", async () => {
    render(<NotificationChoices items={data.notificationChoices().items} />);
    await userEvent.click(screen.getByRole("switch", { name: "Low credit: in the app" }));
    expect(screen.getByRole("switch", { name: "Low credit: in the app" })).toBeChecked();
  });
});

describe("an address mail can't reach", () => {
  it("asks the person to check it, under the address itself", () => {
    render(<Fact label="Email" value="asha@example.com" note={UNDELIVERABLE} />);
    expect(screen.getByText("We couldn't email you: check your address")).toBeInTheDocument();
  });

  it("says nothing while mail reaches it", () => {
    render(<Fact label="Email" value="asha@example.com" />);
    expect(screen.queryByText(UNDELIVERABLE)).not.toBeInTheDocument();
  });
});

describe("a name", () => {
  it("is one to sixty characters once the spaces around it are gone", () => {
    expect(nameProblem("")).toBe("Enter a name.");
    expect(nameProblem("   ")).toBe("Enter a name.");
    expect(nameProblem("A")).toBeUndefined();
    expect(nameProblem(`  ${"x".repeat(60)}  `)).toBeUndefined();
    expect(nameProblem("x".repeat(61))).toBe("Use at most 60 characters.");
  });
});

describe("editing the name", () => {
  it("shows the name, and opens an edit in place", async () => {
    render(<NameRow name="Asha" />);
    expect(screen.getByText("Asha")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Edit your name" }));
    expect(screen.getByLabelText("Name")).toHaveValue("Asha");
    expect(screen.getByRole("button", { name: "Save" })).toBeInTheDocument();
  });

  it("sends the new name trimmed", async () => {
    render(<NameRow name="Asha" />);
    await userEvent.click(screen.getByRole("button", { name: "Edit your name" }));
    await userEvent.clear(screen.getByLabelText("Name"));
    await userEvent.type(screen.getByLabelText("Name"), "  Asha Rao ");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(rename.mutate).toHaveBeenCalledWith("Asha Rao", expect.anything());
  });

  it("refuses an empty or too long name before sending anything", async () => {
    render(<NameRow name="Asha" />);
    await userEvent.click(screen.getByRole("button", { name: "Edit your name" }));
    await userEvent.clear(screen.getByLabelText("Name"));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Enter a name.")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Name"), "x".repeat(61));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Use at most 60 characters.")).toBeInTheDocument();
    expect(rename.mutate).not.toHaveBeenCalled();
  });

  it("closes without sending when nothing changed, and Cancel leaves the name as it was", async () => {
    render(<NameRow name="Asha" />);
    await userEvent.click(screen.getByRole("button", { name: "Edit your name" }));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(rename.mutate).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Edit your name" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Edit your name" }));
    await userEvent.type(screen.getByLabelText("Name"), " Rao");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(within(screen.getByText("Asha").parentElement!).getByText("Asha")).toBeInTheDocument();
    expect(rename.mutate).not.toHaveBeenCalled();
  });
});
