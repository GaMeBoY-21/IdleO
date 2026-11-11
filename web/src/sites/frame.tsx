import { lazy, Suspense, useEffect, type ReactNode } from "react";
import { Route, Switch, useLocation, useSearch } from "wouter";
import { useCachedMe, useMeAfterPaint } from "@/api/queries";
import { AppShell, ShellSkeleton } from "@/components/shell/AppShell";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Skeleton } from "@/components/ui/Skeleton";
import { agreeStep } from "@/features/after-sign-in";
import { NotFound } from "@/pages/NotFound";
import { nav } from "@site/site";
import { screen, SIGN_IN_SCREENS, SignedOutScreen, useSiteAddresses, type Screen } from "./screen";

export { screen, type Screen } from "./screen";

/**
 * What every site of the web app is made of (ADR 0058): its screens, each its own chunk; the screens every site has
 * (signing in, the account); and one frame around the signed-in ones. A site (src/sites/<site>/routes.tsx) names its
 * own screens and which of them are drawn signed in; this file draws them.
 *
 * Every screen is its own chunk (so no first load carries another screen), fetched once the first screen has loaded,
 * so moving between screens is still instant. URLs end in a slash: every screen is a folder with an index.html that
 * any static host serves (scripts/postbuild.ts, src/sites/<site>/screens.ts).
 */

/** The screens every site with an account has, at the same paths: signing in, the Terms, an invite code, and the account. */
export const SHARED_SCREENS = {
  ...SIGN_IN_SCREENS,
  "/account/": screen(() => import("@/features/account/Account").then((m) => m.Account)),
  "/account/appeal/": screen(() => import("@/features/account/Appeal").then((m) => m.Appeal)),
  "/account/data/": screen(() => import("@/features/account/YourData").then((m) => m.YourData)),
  "/account/help/": screen(() => import("@/features/help/Help").then((m) => m.Help)),
  "/account/help/ticket/": screen(() => import("@/features/help/HelpTicket").then((m) => m.HelpTicket)),
  "/account/delete/": screen(() => import("@/features/account/DeleteAccount").then((m) => m.DeleteAccount)),
  "/account/delete/confirm/": screen(() =>
    import("@/features/deletion/ConfirmDeletion").then((m) => m.ConfirmDeletion),
  ),
  "/account/delete/cancel/": screen(() =>
    import("@/features/deletion/CancelDeletion").then((m) => m.CancelDeletion),
  ),
  "/notifications/": screen(() =>
    import("@/features/notifications/Notifications").then((m) => m.Notifications),
  ),
} satisfies Record<string, Screen>;

/** The shared screens drawn inside the signed-in frame; the rest stand alone. */
const SHARED_SIGNED_IN = [
  "/account/",
  "/account/appeal/",
  "/account/data/",
  "/account/help/",
  "/account/help/ticket/",
  "/account/delete/",
  "/notifications/",
];

/** Signed-out screens: their own frame, never the app's bars. */
const SIGNED_OUT = [
  "/sign-in/",
  "/sign-in/finish/",
  "/account/delete/confirm/",
  "/account/delete/cancel/",
  "/agree/",
];

export interface SiteDefinition {
  /** The site's own screens by path; a path with `:id` (`/link/:id/`) is drawn for any value there. */
  screens: Record<string, Screen>;
  /** Which of its own screens are drawn inside the signed-in frame. */
  signedIn: readonly string[];
  /** Which of its own screens are drawn in the signed-out frame, as sign-in is (the welcome). */
  signedOut?: readonly string[];
  /** Which of its own screens stand alone, with a frame of their own (the CLI's sign-in). */
  standalone?: readonly string[];
}

/** The path a screen is filed under: `/link/<id>/` is `/link/:id/`'s. */
function screenPath(path: string, screens: Record<string, Screen>): string {
  if (screens[path]) {
    return path;
  }
  return (
    Object.keys(screens).find((pattern) => pattern.includes(":") && patternOf(pattern).test(path)) ?? path
  );
}

function patternOf(path: string): RegExp {
  return new RegExp(`^${path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/:[a-z]+/g, "[^/]+")}$`);
}

export interface Site {
  App: () => ReactNode;
  /** Loads the code of the screen at `path`, if it has its own; resolves at once for the others. */
  preloadScreen: (path: string) => Promise<unknown>;
}

/** A site's App and its preloader, from its definition. */
export function defineSite(definition: SiteDefinition): Site {
  const screens: Record<string, Screen> = { ...SHARED_SCREENS, ...definition.screens };
  const everyScreen = Object.values(screens);
  const signedIn = [...SHARED_SIGNED_IN, ...definition.signedIn];
  const signedInPattern = new RegExp(
    `^(${signedIn.map((path) => patternOf(path).source.slice(1, -1)).join("|")})$`,
  );
  const signedOut = [...SIGNED_OUT, ...(definition.signedOut ?? [])];
  const standalone = ["/invite/", ...(definition.standalone ?? [])];

  function preloadScreen(path: string): Promise<unknown> {
    const own = screens[screenPath(path, screens)];
    return own ? own.load().catch(() => undefined) : Promise.resolve();
  }

  function SignedInArea() {
    useMeAfterPaint();
    const me = useCachedMe();
    if (me?.status === "suspended") {
      return (
        <RequireAuth fallback={<ShellSkeleton />}>
          <Suspense fallback={<ShellSkeleton />}>
            <HeldArea />
          </Suspense>
        </RequireAuth>
      );
    }
    // An account this site's side isn't open to (ADR 0058) meets one page that takes a code, inside the frame, whose
    // menu leads to the other site. What a visit kept from before sides were named says nothing, and gates nothing.
    const locked = me?.unlocked !== undefined && !me.unlocked.includes(nav.side) && me.status !== "waiting";
    return (
      <RequireAuth fallback={<ShellSkeleton />}>
        <NoticesGate />
        <AppShell>
          <Suspense fallback={<Skeleton className="h-16 w-full" />}>
            {locked ? (
              <Unlock />
            ) : (
              <Switch>
                {signedIn.map((path) => (
                  <Route key={path} path={path} component={screens[path]!.Screen} />
                ))}
              </Switch>
            )}
          </Suspense>
        </AppShell>
      </RequireAuth>
    );
  }

  function App() {
    useSiteAddresses(everyScreen);

    return (
      <Switch>
        {signedOut.map((at) => {
          const Own = screens[at]!.Screen;
          return (
            <Route key={at} path={at}>
              <SignedOutScreen>
                <Own />
              </SignedOutScreen>
            </Route>
          );
        })}
        {standalone.map((at) => (
          <Route key={at} path={at} component={screens[at]!.Screen} />
        ))}
        <Route path={signedInPattern}>
          <SignedInArea />
        </Route>
        <Route>
          <NotFound />
        </Route>
      </Switch>
    );
  }

  return { App, preloadScreen };
}

/**
 * The Terms or privacy notice changed since this person agreed (ADR 0037): they're asked once, before anything else,
 * as soon as the account says so: at once from what the screen already has, else once the screen has painted.
 */
function NoticesGate() {
  useMeAfterPaint();
  const me = useCachedMe();
  const [path, navigate] = useLocation();
  const search = useSearch();
  const pending = me !== undefined && !me.accepted;
  useEffect(() => {
    if (pending) {
      navigate(agreeStep(`${path}${search ? `?${search}` : ""}`), { replace: true });
    }
  }, [pending, path, search, navigate]);
  return null;
}

// Only an account that hasn't unlocked this site's side draws this (ADR 0058), so it's a chunk of its own.
const Unlock = lazy(() => import("@/features/invite/Unlock").then((m) => ({ default: m.Unlock })));

// Only a suspended or banned account ever draws these, so they are chunks of their own, not part of any first load.
const HeldShell = lazy(() => import("@/features/held/HeldAccount").then((m) => ({ default: m.HeldShell })));
const HeldAccount = lazy(() =>
  import("@/features/held/HeldAccount").then((m) => ({ default: m.HeldAccount })),
);

/**
 * A suspended or banned account (ADR 0038) is told so, and keeps three screens: its data, deleting the account, and writing
 * to us. Everywhere else it meets the same plain message. It has no tabs, credit or bell to ask the server for.
 */
function HeldArea() {
  return (
    <HeldShell>
      <Suspense fallback={<Skeleton className="h-16 w-full" />}>
        <Switch>
          <Route path="/account/data/" component={SHARED_SCREENS["/account/data/"].Screen} />
          <Route path="/account/delete/" component={SHARED_SCREENS["/account/delete/"].Screen} />
          <Route path="/account/help/" component={SHARED_SCREENS["/account/help/"].Screen} />
          <Route path="/account/help/ticket/" component={SHARED_SCREENS["/account/help/ticket/"].Screen} />
          <Route path="/account/appeal/" component={SHARED_SCREENS["/account/appeal/"].Screen} />
          <Route component={HeldAccount} />
        </Switch>
      </Suspense>
    </HeldShell>
  );
}
