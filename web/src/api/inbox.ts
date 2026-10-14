import { useInfiniteQuery, useMutation, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { api, data } from "./client";
import { keys } from "./queries";
import type { components } from "./schema.gen";

export type InboxPage = components["schemas"]["InboxPage"];
export type InboxItem = components["schemas"]["InboxItem"];

export const INBOX_PAGE = 20;

/** How often the list is asked again while a tab is open and visible, and when it regains focus (ADR 0026). */
export const INBOX_POLL_MS = 30_000;

export const inboxKeys = { list: ["inbox"] as const };

/**
 * The person's messages, newest first, a page at a time, with how many are unread. It is the one source of the
 * bell's count, the banners and the list: asked again every 30 s while the tab is visible and when it regains
 * focus, so a message from a session that ended reaches an open app. `enabled` false holds the first fetch back
 * (useAfterFirstScreen): the screen paints before they're asked for.
 */
export function useInbox(enabled = true) {
  return useInfiniteQuery({
    queryKey: inboxKeys.list,
    enabled,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/notifications", {
          params: { query: { limit: INBOX_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? null,
    staleTime: 10_000,
    refetchInterval: INBOX_POLL_MS,
  });
}

/** Every message in the pages loaded so far, newest first. */
export const itemsOf = (inbox: InfiniteData<InboxPage> | undefined): InboxItem[] =>
  inbox?.pages.flatMap((page) => page.items) ?? [];

/** How many messages are unread, as the server last said. */
export const unreadOf = (inbox: InfiniteData<InboxPage> | undefined): number | undefined =>
  inbox?.pages[0]?.unread;

/**
 * What to mark read: `{ id }`, just the message opened, or `{ upTo }`, every message up to and including it (the
 * newest one's id, for "Mark all read").
 */
export type ReadChoice = { id: string } | { upTo: string };

/**
 * Marks messages read, as `ReadChoice` says. The cache moves only once the server has answered, with its own count of
 * what is still unread.
 */
export function useMarkRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (choice: ReadChoice) => data(api.POST("/v1/notifications/read", { body: choice })),
    onSuccess: ({ unread }, choice) => {
      client.setQueryData<InfiniteData<InboxPage>>(
        inboxKeys.list,
        (inbox) => inbox && withRead(inbox, choice, unread),
      );
      client.setQueryData<components["schemas"]["MeResponse"]>(
        keys.me,
        (me) => me && { ...me, unreadNotifications: unread },
      );
    },
  });
}

/**
 * The list after the server said the message `choice` names is read (or everything up to it), and `unread` are left.
 * UUIDv7 ids are in time order, and as lower-case text they sort the same way.
 */
export function withRead(
  inbox: InfiniteData<InboxPage>,
  choice: ReadChoice,
  unread: number,
): InfiniteData<InboxPage> {
  const isRead = (item: InboxItem) => ("id" in choice ? item.id === choice.id : item.id <= choice.upTo);
  return {
    ...inbox,
    pages: inbox.pages.map((page) => ({
      ...page,
      unread,
      items: page.items.map((item) => (isRead(item) ? { ...item, read: true } : item)),
    })),
  };
}
