import { defineSite, screen } from "../frame";

/**
 * The renter app (app.idleo.in, ADR 0058): booking, sessions, files and credit. No hosting screen is here, and no console:
 * its old /admin/ addresses redirect to the admin site (src/sites/admin/paths.ts).
 */
const screens = {
  "/": screen(() => import("@/features/home/Home").then((m) => m.Home)),
  "/credit/": screen(() => import("@/features/credit/CreditHistory").then((m) => m.CreditHistory)),
  "/session/": screen(() => import("@/features/session/SessionDetail").then((m) => m.SessionDetail)),
  "/sessions/": screen(() => import("@/features/sessions/AllSessions").then((m) => m.AllSessions)),
  "/book/": screen(() => import("@/features/book/Book").then((m) => m.Book)),
  "/files/": screen(() => import("@/features/files/MyFiles").then((m) => m.MyFiles)),
  "/welcome/": screen(() => import("@/features/home/Welcome").then((m) => m.Welcome)),
  "/account/keys/": screen(() => import("@/features/account/ApiKeys").then((m) => m.ApiKeys)),
  "/account/reports/": screen(() => import("@/features/account/Reports").then((m) => m.Reports)),
  "/account/images/": screen(() => import("@/features/account/MyImages").then((m) => m.MyImages)),
  "/cli-login/": screen(() => import("@/features/cli-login/CliLogin").then((m) => m.CliLogin)),
};

export const { App, preloadScreen } = defineSite({
  screens,
  signedIn: [
    "/",
    "/credit/",
    "/session/",
    "/sessions/",
    "/book/",
    "/files/",
    "/account/keys/",
    "/account/reports/",
    "/account/images/",
  ],
  signedOut: ["/welcome/"],
  standalone: ["/cli-login/"],
});
