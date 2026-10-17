import { describe, expect, it } from "vitest";
import { entryLinks, LANDING, OLD_LANDING } from "./paths";

describe("the links into the app", () => {
  it("are built from the app's address, local or deployed, with or without a trailing slash", () => {
    for (const app of ["http://localhost:3000", "http://localhost:3000/"]) {
      expect(entryLinks(app)).toEqual({
        logIn: "http://localhost:3000/sign-in/",
        getStarted: "http://localhost:3000/sign-in/?start=1",
        startHosting: "http://localhost:3000/hosting/",
      });
    }
    expect(entryLinks("https://app.idleo.in")).toEqual({
      logIn: "https://app.idleo.in/sign-in/",
      getStarted: "https://app.idleo.in/sign-in/?start=1",
      startHosting: "https://app.idleo.in/hosting/",
    });
  });

  it("keep the website's front door at the root and the old landing page elsewhere, for the redirect", () => {
    expect(LANDING).toBe("/");
    expect(OLD_LANDING).toBe("/about/");
  });
});
