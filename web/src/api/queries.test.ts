import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { data } from "@/test/fixtures";
import { isChanging, keys, writeSession, type SessionList } from "./queries";

describe("which sessions are watched", () => {
  it("watches a session until the API says it's settled", () => {
    expect(isChanging({ settled: false })).toBe(true);
    expect(isChanging({ settled: true })).toBe(false);
  });
});

describe("a session's newer state", () => {
  it("goes into its own entry and every list that holds it, and nowhere else", () => {
    const client = new QueryClient();
    const home: SessionList = { sessions: [data.running, data.completed], nextCursor: null };
    const active = { pages: [{ sessions: [data.running], nextCursor: null }], pageParams: [null] };
    const ended = { pages: [{ sessions: [data.completed], nextCursor: null }], pageParams: [null] };
    client.setQueryData(keys.sessions, home);
    client.setQueryData(keys.sessionsList(true), active);
    client.setQueryData(keys.sessionsList(false), ended);

    const newer = { ...data.running, accruedPaise: data.running.accruedPaise + 14 };
    writeSession(client, newer);

    expect(client.getQueryData(keys.session(newer.id))).toEqual(newer);
    expect(client.getQueryData<SessionList>(keys.sessions)!.sessions[0]).toEqual(newer);
    expect(client.getQueryData<typeof active>(keys.sessionsList(true))!.pages[0]!.sessions[0]).toEqual(newer);
    expect(client.getQueryData(keys.sessionsList(false))).toBe(ended);
  });
});
