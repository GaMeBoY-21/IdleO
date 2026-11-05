import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Router, type BaseLocationHook } from "wouter";
import { memoryLocation } from "wouter/memory-location";
import { adminApi } from "./api";
import { PaletteProvider, usePaletteActions } from "./Palette";
import { PermissionsProvider } from "./permissions";

const SCREENS = [
  { href: "/admin/payouts/", label: "Payouts", needs: "payouts.read" },
  { href: "/admin/approvals/", label: "Approvals", needs: "approvals.read" },
  { href: "/admin/users/", label: "Users", needs: "users.read" },
];

const suspend = vi.fn();
const ACTIONS = [
  { label: "Suspend this user…", permission: "users.suspend", run: suspend },
  { label: "Grant credit…", permission: "money.adjust", run: vi.fn() },
];

function Page() {
  usePaletteActions(ACTIONS);
  return <p>The page</p>;
}

function Console({ held, location }: { held: string[]; location: { hook: BaseLocationHook } }) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <Router hook={location.hook}>
        <PermissionsProvider permissions={held}>
          <PaletteProvider screens={SCREENS}>
            <Page />
          </PaletteProvider>
        </PermissionsProvider>
      </Router>
    </QueryClientProvider>
  );
}

function answer(body: unknown) {
  return { data: body, response: new Response(null, { status: 200 }) } as never;
}

describe("the command palette", () => {
  beforeEach(() => {
    localStorage.clear();
    Element.prototype.scrollIntoView = vi.fn();
    suspend.mockClear();
  });
  afterEach(() => vi.restoreAllMocks());

  it("opens with Ctrl-K, closes with esc, and offers only what the admin may do", async () => {
    const user = userEvent.setup();
    render(<Console held={["users.read", "users.suspend"]} location={memoryLocation({ path: "/admin/" })} />);
    await user.keyboard("{Control>}k{/Control}");
    expect(screen.getByRole("option", { name: "Go to Users" })).toBeVisible();
    expect(screen.getByRole("option", { name: "Suspend this user…" })).toBeVisible();
    expect(screen.queryByRole("option", { name: "Go to Payouts" })).toBeNull();
    expect(screen.queryByRole("option", { name: "Open approvals" })).toBeNull();
    expect(screen.queryByRole("option", { name: "Grant credit…" })).toBeNull();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("finds by what's typed, grouped by kind, moves with the arrows and opens with enter", async () => {
    const search = vi.spyOn(adminApi, "GET").mockResolvedValue(
      answer({
        groups: [
          { type: "host", hits: [{ page: "host", id: "h1", title: "Studio PC", detail: "active" }] },
          { type: "user", hits: [{ page: "user", id: "u1", title: "Asha", detail: "as•••@gmail.com" }] },
        ],
        unanswered: ["payout"],
      }),
    );
    const location = memoryLocation({ path: "/admin/", record: true });
    const user = userEvent.setup();
    render(<Console held={["users.read"]} location={location} />);
    await user.keyboard("{Meta>}k{/Meta}");
    await user.keyboard("asha");
    expect(await screen.findByRole("group", { name: "Hosts" })).toBeVisible();
    expect(screen.getByRole("group", { name: "Users" })).toBeVisible();
    expect(screen.getByText(/didn't answer in time/)).toHaveTextContent("Payouts");
    expect(screen.getByRole("option", { name: /Studio PC/ })).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("option", { name: /Asha/ })).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(location.history.at(-1)).toBe("/admin/users/?id=u1"));
    expect(search).toHaveBeenCalledTimes(1);

    // Opened once, it's a recent item, as masked as it was shown.
    await user.keyboard("{Control>}k{/Control}");
    expect(screen.getByRole("group", { name: "Recent" })).toHaveTextContent("as•••@gmail.com");
  });

  it("runs a page's action through the page, so its own sheet opens", async () => {
    const user = userEvent.setup();
    render(<Console held={["users.suspend"]} location={memoryLocation({ path: "/admin/" })} />);
    await user.keyboard("{Control>}k{/Control}");
    await user.keyboard("suspend");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(suspend).toHaveBeenCalledTimes(1));
  });

  it("says so when nothing is found", async () => {
    vi.spyOn(adminApi, "GET").mockResolvedValue(answer({ groups: [], unanswered: [] }));
    const user = userEvent.setup();
    render(<Console held={[]} location={memoryLocation({ path: "/admin/" })} />);
    await user.keyboard("{Control>}k{/Control}");
    await user.keyboard("nobody");
    expect(await screen.findByText("Nothing found for that.")).toBeVisible();
  });
});
