import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { InfiniteData } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { withRead, type InboxItem, type InboxPage } from "@/api/inbox";
import { NotificationBanners } from "@/components/shell/NotificationBanners";
import { NotificationBell } from "@/components/shell/NotificationBell";
import { Notifications } from "./Notifications";

const state = vi.hoisted(() => ({
  inbox: {
    data: undefined as InfiniteData<InboxPage> | undefined,
    isError: false,
    error: null as Error | null,
    isFetching: false,
    isRefetchError: false,
    dataUpdatedAt: 0,
    hasNextPage: false,
    isFetchingNextPage: false,
    isFetchNextPageError: false,
    refetch: vi.fn(),
    fetchNextPage: vi.fn(),
  },
  markRead: { mutate: vi.fn(), isPending: false, isError: false, error: null as Error | null },
  cachedMe: undefined as { unreadNotifications: number } | undefined,
  path: "/",
  afterFirstScreen: true,
  inboxAsked: 0,
}));

vi.mock("@/api/inbox", async (original) => ({
  ...(await original<typeof import("@/api/inbox")>()),
  useInbox: () => {
    state.inboxAsked++;
    return state.inbox;
  },
  useMarkRead: () => state.markRead,
}));
vi.mock("@/api/queries", () => ({ useCachedMe: () => ({ data: state.cachedMe }) }));
vi.mock("@/lib/after-first-screen", () => ({
  useAfterFirstScreen: () => state.afterFirstScreen,
  useAfterPaint: () => true,
}));
vi.mock("wouter", () => ({
  Link: ({
    href,
    children,
    onClick,
    ...rest
  }: {
    href: string;
    children: React.ReactNode;
    onClick?: () => void;
  }) => (
    <a
      href={href}
      onClick={(event) => {
        // jsdom can't navigate; what's tested is what the link does besides.
        event.preventDefault();
        onClick?.();
      }}
      {...rest}
    >
      {children}
    </a>
  ),
  useLocation: () => [state.path],
}));
vi.mock("@/lib/title", () => ({ useTitle: () => undefined }));
vi.mock("@/components/ui/Time", () => ({ RelativeTime: () => <span>5 minutes ago</span> }));

const item = (n: number, over: Partial<InboxItem> = {}): InboxItem => ({
  id: `01a13000-0000-7000-8000-00000000000${n}`,
  kind: "session-completed",
  title: `Title ${n}`,
  body: `Body ${n}`,
  link: `/session/?id=${n}`,
  createdAt: "2026-10-12T10:00:00Z",
  read: false,
  ...over,
});

const pages = (items: InboxItem[], unread: number): InfiniteData<InboxPage> => ({
  pages: [{ items, nextCursor: null, unread }],
  pageParams: [null],
});

beforeEach(() => {
  state.inbox.data = undefined;
  state.inbox.isError = false;
  state.inbox.hasNextPage = false;
  state.markRead.mutate.mockReset();
  state.cachedMe = undefined;
  state.path = "/";
  state.afterFirstScreen = true;
  state.inboxAsked = 0;
});

describe("the bell", () => {
  it("shows no count when nothing is unread, and says so in its name", () => {
    state.inbox.data = pages([item(1, { read: true })], 0);
    render(<NotificationBell />);
    expect(screen.getByRole("link", { name: "Notifications" })).toHaveAttribute("href", "/notifications/");
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });

  it("counts what is unread, and caps what it draws at 9+", () => {
    state.inbox.data = pages([item(1)], 3);
    const { rerender } = render(<NotificationBell />);
    expect(screen.getByRole("link", { name: "Notifications, 3 unread" })).toHaveTextContent("3");

    state.inbox.data = pages([item(1)], 23);
    rerender(<NotificationBell />);
    expect(screen.getByRole("link", { name: "Notifications, 23 unread" })).toHaveTextContent("9+");
  });

  it("shows what the account last said until the list arrives", () => {
    state.cachedMe = { unreadNotifications: 2 };
    render(<NotificationBell />);
    expect(screen.getByRole("link", { name: "Notifications, 2 unread" })).toBeInTheDocument();
  });
});

describe("before the first screen has loaded", () => {
  it("the bell is only a link and the banners nothing: no query is made for the first paint to wait behind", () => {
    state.afterFirstScreen = false;
    state.inbox.data = pages([item(1)], 3);
    render(
      <>
        <NotificationBell />
        <NotificationBanners />
      </>,
    );
    expect(screen.getByRole("link", { name: "Notifications" })).toHaveAttribute("href", "/notifications/");
    expect(state.inboxAsked).toBe(0);
    expect(screen.queryByRole("region", { name: "New notifications" })).not.toBeInTheDocument();
  });
});

describe("the list", () => {
  it("shows each message newest first, unread ones named as such", () => {
    state.inbox.data = pages([item(2), item(1, { read: true })], 1);
    render(<Notifications />);

    const rows = screen.getAllByRole("listitem");
    expect(within(rows[0]!).getByText("Title 2")).toBeInTheDocument();
    expect(within(rows[0]!).getByText("Unread:")).toBeInTheDocument();
    expect(within(rows[1]!).queryByText("Unread:")).not.toBeInTheDocument();
    expect(within(rows[0]!).getByRole("link")).toHaveAttribute("href", "/session/?id=2");
    expect(screen.getByText("1 unread")).toBeInTheDocument();
  });

  it("opening an unread message marks it read, and one already read is left alone", async () => {
    state.inbox.data = pages([item(2), item(1, { read: true })], 1);
    render(<Notifications />);
    const rows = screen.getAllByRole("listitem");
    await userEvent.click(within(rows[1]!).getByRole("link"));
    expect(state.markRead.mutate).not.toHaveBeenCalled();
    await userEvent.click(within(rows[0]!).getByRole("link"));
    expect(state.markRead.mutate).toHaveBeenCalledWith(item(2).id);
  });

  it("marks everything read with the newest one's id, and is off when nothing is unread", async () => {
    state.inbox.data = pages([item(3), item(2), item(1)], 3);
    const { rerender } = render(<Notifications />);
    await userEvent.click(screen.getByRole("button", { name: "Mark all read" }));
    expect(state.markRead.mutate).toHaveBeenCalledWith(item(3).id);

    state.inbox.data = pages([item(3, { read: true })], 0);
    rerender(<Notifications />);
    expect(screen.getByRole("button", { name: "Mark all read" })).toBeDisabled();
    expect(screen.getByText("All read")).toBeInTheDocument();
  });

  it("offers older ones when there are more", async () => {
    state.inbox.data = pages([item(1)], 1);
    state.inbox.hasNextPage = true;
    render(<Notifications />);
    await userEvent.click(screen.getByRole("button", { name: "Show older" }));
    expect(state.inbox.fetchNextPage).toHaveBeenCalled();
  });

  it("says why it's empty", () => {
    state.inbox.data = pages([], 0);
    render(<Notifications />);
    expect(screen.getByText("Nothing yet")).toBeInTheDocument();
  });

  it("says it's loading, and says when it couldn't load, with a way to try again", async () => {
    const { rerender } = render(<Notifications />);
    expect(screen.getByRole("status", { name: "Loading your notifications" })).toBeInTheDocument();

    state.inbox.isError = true;
    state.inbox.error = new Error("down");
    rerender(<Notifications />);
    await userEvent.click(screen.getByRole("button", { name: /Try again/ }));
    expect(state.inbox.refetch).toHaveBeenCalled();
  });

  it("keeps a skeleton as tall as a row: every row reserves its body's two lines", () => {
    state.inbox.data = pages([item(1, { body: "Short." })], 1);
    const { unmount } = render(<Notifications />);
    expect(screen.getByText("Short.").className).toMatch(/min-h-10/);
    unmount();
    state.inbox.data = undefined;
    const { container } = render(<Notifications />);
    expect(container.querySelector(".min-h-10")).not.toBeNull();
  });
});

describe("what marking read changes", () => {
  it("reads every message up to the one named, and keeps the server's count", () => {
    const before = pages([item(3), item(2), item(1)], 3);
    const after = withRead(before, item(2).id, 1);
    expect(after.pages[0]!.items.map((one) => one.read)).toEqual([false, true, true]);
    expect(after.pages[0]!.unread).toBe(1);
    expect(before.pages[0]!.items.every((one) => !one.read)).toBe(true);
  });
});

describe("banners for what arrives while the app is open", () => {
  it("says nothing about what was already waiting, then shows a new arrival, pushing content down", () => {
    state.inbox.data = pages([item(1)], 1);
    const { rerender } = render(<NotificationBanners />);
    expect(screen.queryByRole("region", { name: "New notifications" })).not.toBeInTheDocument();

    state.inbox.data = pages([item(2), item(1)], 2);
    rerender(<NotificationBanners />);
    const banners = screen.getByRole("region", { name: "New notifications" });
    expect(within(banners).getByText("Title 2")).toBeInTheDocument();
    expect(within(banners).queryByText("Title 1")).not.toBeInTheDocument();
  });

  it("opening one marks it read; dismissing only hides it", async () => {
    state.inbox.data = pages([item(1)], 1);
    const { rerender } = render(<NotificationBanners />);
    state.inbox.data = pages([item(3), item(2), item(1)], 3);
    rerender(<NotificationBanners />);

    await userEvent.click(screen.getByRole("button", { name: "Dismiss: Title 3" }));
    expect(screen.queryByText("Title 3")).not.toBeInTheDocument();
    expect(state.markRead.mutate).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("link", { name: "Title 2" }));
    expect(state.markRead.mutate).toHaveBeenCalledWith(item(2).id);
  });

  it("drops a banner once its message has been read elsewhere", () => {
    state.inbox.data = pages([item(1)], 1);
    const { rerender } = render(<NotificationBanners />);
    state.inbox.data = pages([item(2), item(1)], 2);
    rerender(<NotificationBanners />);
    expect(screen.getByText("Title 2")).toBeInTheDocument();

    state.inbox.data = pages([item(2, { read: true }), item(1)], 1);
    rerender(<NotificationBanners />);
    expect(screen.queryByText("Title 2")).not.toBeInTheDocument();
  });

  it("shows three at most and points to the rest, and none on the list's own screen", () => {
    state.inbox.data = pages([item(1)], 1);
    const { rerender } = render(<NotificationBanners />);
    state.inbox.data = pages([item(5), item(4), item(3), item(2), item(1)], 5);
    rerender(<NotificationBanners />);
    expect(screen.getAllByRole("status")).toHaveLength(3);
    expect(screen.getByText("1 more in your notifications")).toBeInTheDocument();

    state.path = "/notifications/";
    rerender(<NotificationBanners />);
    expect(screen.queryByRole("region", { name: "New notifications" })).not.toBeInTheDocument();
  });
});
