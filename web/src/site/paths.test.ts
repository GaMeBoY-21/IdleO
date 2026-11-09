import { describe, expect, it } from "vitest";
import { entryLinks, LANDING, OLD_LANDING } from "./paths";

describe("the links into the app and the host site", () => {
  it("are built from the app's and the host site's addresses, local or deployed, with or without a trailing slash", () => {
    for (const [app, host] of [
      ["http://localhost:3000", "http://localhost:3003"],
      ["http://localhost:3000/", "http://localhost:3003/"],
    ] as const) {
      expect(entryLinks(app, host)).toEqual({
        logIn: "http://localhost:3000/sign-in/",
        getStarted: "http://localhost:3000/sign-in/?start=1",
        startHosting: "http://localhost:3003/",
      });
    }
    expect(entryLinks("https://app.idleo.in", "https://host.idleo.in")).toEqual({
      logIn: "https://app.idleo.in/sign-in/",
      getStarted: "https://app.idleo.in/sign-in/?start=1",
      startHosting: "https://host.idleo.in/",
    });
  });

  it("keep the website's front door at the root and the old landing page elsewhere, for the redirect", () => {
    expect(LANDING).toBe("/");
    expect(OLD_LANDING).toBe("/about/");
  });
});
