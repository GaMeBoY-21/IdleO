import { Suspense } from "react";
import { Redirect, Route, Switch } from "wouter";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { CONSOLE_SCREENS } from "@/features/admin/console-screens";
import { NotFound } from "@/pages/NotFound";
import { screen, SIGN_IN_SCREENS, SignedOutScreen, useSiteAddresses, type Screen } from "../screen";
import { CONSOLE } from "./paths";

/**
 * The admin site (admin.idleo.in, ADR 0058): the console (ADR 0027) and signing in, nothing of renting or hosting. The
 * console keeps its /admin/ addresses and draws its own frame; this site's root leads to it. A signed-out visitor signs
 * in here and comes back; the console then asks for its own second factor (Gate).
 */
const adminConsole = screen(() => import("@/features/admin/AdminConsole").then((m) => m.AdminConsole));
const CONSOLE_PATH = /^\/admin\//;
const SIGNED_OUT = ["/sign-in/", "/sign-in/finish/", "/agree/"] as const;
const everyScreen = [...Object.values(SIGN_IN_SCREENS), adminConsole, ...Object.values(CONSOLE_SCREENS)];
const signIn: Record<string, Screen> = SIGN_IN_SCREENS;

/** The code of the screen at `path`: on a console address, the console's frame and that screen's own, side by side. */
export function preloadScreen(path: string): Promise<unknown> {
  const own = CONSOLE_PATH.test(path)
    ? [adminConsole, ...(CONSOLE_SCREENS[path] ? [CONSOLE_SCREENS[path]] : [])]
    : signIn[path]
      ? [signIn[path]]
      : [];
  return Promise.all(own.map((screen) => screen.load().catch(() => undefined)));
}

export function App() {
  useSiteAddresses(everyScreen);
  const Invite = SIGN_IN_SCREENS["/invite/"].Screen;
  return (
    <Switch>
      {SIGNED_OUT.map((at) => {
        const Own = SIGN_IN_SCREENS[at].Screen;
        return (
          <Route key={at} path={at}>
            <SignedOutScreen>
              <Own />
            </SignedOutScreen>
          </Route>
        );
      })}
      <Route path="/invite/" component={Invite} />
      {/* Signed in first, so the pre-rendered frame is this empty one, never the console's chunk. */}
      <Route path={CONSOLE_PATH}>
        <RequireAuth fallback={<div className="min-h-dvh" />}>
          <Suspense fallback={<div className="min-h-dvh" />}>
            <adminConsole.Screen />
          </Suspense>
        </RequireAuth>
      </Route>
      <Route path="/">
        <Redirect to={CONSOLE} replace />
      </Route>
      <Route>
        <NotFound />
      </Route>
    </Switch>
  );
}
