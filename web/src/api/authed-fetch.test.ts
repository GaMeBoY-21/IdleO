import { describe, expect, it, vi } from "vitest";
import { authedFetch, SignedOutError, type TokenSource } from "./authed-fetch";

function tokens(...issued: (string | null)[]): TokenSource & { signOut: ReturnType<typeof vi.fn> } {
  const queue = [...issued];
  return {
    getIdToken: vi.fn(async () => queue.shift() ?? null),
    signOut: vi.fn(async () => undefined),
  };
}

const respond = (...statuses: number[]) => {
  const queue = [...statuses];
  return vi.fn(
    async (request: Request) =>
      new Response(JSON.stringify({ auth: request.headers.get("Authorization") }), {
        status: queue.shift() ?? 500,
      }),
  );
};

describe("authedFetch", () => {
  it("sends the ID token as a bearer token", async () => {
    const fetcher = respond(200);
    const response = await authedFetch(tokens("first"), fetcher)(new Request("https://api.test/v1/me"));
    expect(await response.json()).toEqual({ auth: "Bearer first" });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("refreshes the token and retries once after a 401", async () => {
    const source = tokens("stale", "fresh");
    const fetcher = respond(401, 200);
    const response = await authedFetch(source, fetcher)(new Request("https://api.test/v1/wallet"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ auth: "Bearer fresh" });
    expect(source.getIdToken).toHaveBeenNthCalledWith(2, true);
    expect(source.signOut).not.toHaveBeenCalled();
  });

  it("keeps the request body for the retry", async () => {
    const bodies: string[] = [];
    const fetcher = vi.fn(async (request: Request) => {
      bodies.push(await request.text());
      return new Response(null, { status: bodies.length === 1 ? 401 : 201 });
    });
    const request = new Request("https://api.test/v1/sessions", { method: "POST", body: '{"a":1}' });
    expect((await authedFetch(tokens("a", "b"), fetcher)(request)).status).toBe(201);
    expect(bodies).toEqual(['{"a":1}', '{"a":1}']);
  });

  it("signs out cleanly when the refreshed token is refused too", async () => {
    const source = tokens("stale", "fresh");
    await expect(
      authedFetch(source, respond(401, 401))(new Request("https://api.test/v1/me")),
    ).rejects.toBeInstanceOf(SignedOutError);
    expect(source.signOut).toHaveBeenCalledWith("expired");
  });

  it("signs out when there's no token to send", async () => {
    const source = tokens(null);
    const fetcher = respond(200);
    await expect(authedFetch(source, fetcher)(new Request("https://api.test/v1/me"))).rejects.toBeInstanceOf(
      SignedOutError,
    );
    expect(fetcher).not.toHaveBeenCalled();
    expect(source.signOut).toHaveBeenCalledWith("expired");
  });

  it("passes other errors through untouched, for the views to show", async () => {
    const source = tokens("first");
    const response = await authedFetch(source, respond(503))(new Request("https://api.test/v1/me"));
    expect(response.status).toBe(503);
    expect(source.signOut).not.toHaveBeenCalled();
  });
});
