import { afterEach, describe, expect, it } from "vitest";
import { ApiError } from "./errors";
import { sendToInviteStep } from "./invite-required";
import { createQueryClient, keys } from "./queries";

const inviteRequired = () => ApiError.of(403, { type: "/problems/invite-required", title: "Invite" });

afterEach(() => window.history.replaceState(null, "", "/"));

describe("an account without an invite code", () => {
  it("is taken to the invite step, and brought back to where it was", () => {
    window.history.replaceState(null, "", "/credit/?x=1");
    expect(sendToInviteStep(inviteRequired())).toBe(true);
    expect(window.location.pathname + window.location.search).toBe("/invite/?next=%2Fcredit%2F%3Fx%3D1");
  });

  it("is taken from Home to the plain invite step", () => {
    expect(sendToInviteStep(inviteRequired())).toBe(true);
    expect(window.location.pathname + window.location.search).toBe("/invite/");
  });

  it("stays where it is when it's already there", () => {
    window.history.replaceState(null, "", "/invite/?next=%2Fbook%2F");
    sendToInviteStep(inviteRequired());
    expect(window.location.search).toBe("?next=%2Fbook%2F");
  });

  it("leaves every other error alone", () => {
    window.history.replaceState(null, "", "/credit/");
    expect(sendToInviteStep(ApiError.of(403, { type: "/problems/account-suspended" }))).toBe(false);
    expect(sendToInviteStep(new Error("network"))).toBe(false);
    expect(window.location.pathname).toBe("/credit/");
  });

  it("is waiting from then on, whatever the cache said", async () => {
    const client = createQueryClient();
    client.setQueryData(keys.me, {
      id: "a",
      status: "active",
      signInMethods: [],
      createdAt: "2026-10-01T00:00:00Z",
    });
    await client
      .query({ queryKey: ["x"], queryFn: () => Promise.reject(inviteRequired()), retry: false })
      .catch(() => undefined);
    expect(client.getQueryData<{ status: string }>(keys.me)?.status).toBe("waiting");
  });
});
