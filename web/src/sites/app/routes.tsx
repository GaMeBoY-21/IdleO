import { Route } from "wouter";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Suspense } from "react";
import { defineSite, screen } from "../frame";

/** The renter app (app.idleo.in, ADR 0058): booking, sessions, files and credit. No hosting screen is here. */
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

/**
 * The admin console (ADR 0027): one chunk of its own, loaded only on an /admin/ address, and never with the other
 * screens after the first one, so a renter never downloads it.
 */
const admin = screen(() => import("@/features/admin/AdminConsole").then((m) => m.AdminConsole));
const ADMIN_PATH = /^\/admin\//;

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
  // Signed in first, so the pre-rendered frame is this empty one, never the console's chunk.
  before: (
    <Route path={ADMIN_PATH}>
      <RequireAuth fallback={<div className="min-h-dvh" />}>
        <Suspense fallback={<div className="min-h-dvh" />}>
          <admin.Screen />
        </Suspense>
      </RequireAuth>
    </Route>
  ),
  beforeMatches: (path) => (ADMIN_PATH.test(path) ? admin : null),
});
