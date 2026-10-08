import { lazy, Suspense, useEffect, type ComponentType, type ReactNode } from "react";
import { Route, Switch, useLocation } from "wouter";
import { AppShell, ShellSkeleton } from "@/components/shell/AppShell";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Skeleton } from "@/components/ui/Skeleton";
import { Home } from "@/features/home/Home";
import { SignIn } from "@/features/sign-in/SignIn";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { afterFirstScreen } from "@/lib/after-first-screen";
import { NotFound } from "@/pages/NotFound";

/**
 * The app's screens (ADR 0018). Home and sign-in, where every visit starts, are in the first load; every other
 * screen is its own chunk, fetched once the first screen has loaded, so moving between screens is still instant. URLs end in a slash: every
 * screen is a folder with an index.html that any static host serves (scripts/postbuild.ts, src/screens.ts).
 */
/**
 * A screen in its own chunk. Once its code has arrived it's drawn directly; before that, through React.lazy. React
 * holds back a suspended screen's first paint by up to 300 ms even when its code is already there, so the screen a
 * page opens on is loaded before the first render (preloadScreen, from src/main.tsx) and never suspends.
 */
function screen<P extends object>(load: () => Promise<ComponentType<P>>) {
  let loaded: ComponentType<P> | null = null;
  const fetch = () =>
    load().then((component) => {
      loaded = component;
      return component;
    });
  const Lazy = lazy(() => fetch().then((component) => ({ default: component })));
  const Screen = (props: P) => {
    const Component = loaded ?? Lazy;
    return <Component {...props} />;
  };
  return { load: fetch, Screen };
}

const screens = {
  "/credit/": screen(() => import("@/features/credit/CreditHistory").then((m) => m.CreditHistory)),
  "/session/": screen(() => import("@/features/session/SessionDetail").then((m) => m.SessionDetail)),
  "/sessions/": screen(() => import("@/features/sessions/AllSessions").then((m) => m.AllSessions)),
  "/book/": screen(() => import("@/features/book/Book").then((m) => m.Book)),
  "/sign-in/finish/": screen(() => import("@/features/sign-in/FinishSignIn").then((m) => m.FinishSignIn)),
  "/welcome/": screen(() => import("@/features/home/Welcome").then((m) => m.Welcome)),
};

/** Loads the code of the screen at `path`, if it has its own; resolves at once for the others. */
export function preloadScreen(path: string): Promise<unknown> {
  const own = (screens as Record<string, { load: () => Promise<unknown> } | undefined>)[path];
  return own ? own.load().catch(() => undefined) : Promise.resolve();
}

const CreditHistory = screens["/credit/"].Screen;
const AllSessions = screens["/sessions/"].Screen;
const SessionDetail = screens["/session/"].Screen;
const Book = screens["/book/"].Screen;
const FinishSignIn = screens["/sign-in/finish/"].Screen;
const Welcome = screens["/welcome/"].Screen;

/** The signed-in screens' paths: one frame around all of them, which stays put while the content changes. */
const SIGNED_IN = /^\/(credit\/|session\/|sessions\/|book\/)?$/;

function SignedOutScreen({ children }: { children: ReactNode }) {
  return (
    <Suspense
      fallback={
        <SignInFrame title="">
          <Skeleton className="h-touch w-full" />
        </SignInFrame>
      }
    >
      {children}
    </Suspense>
  );
}

function SignedInArea() {
  return (
    <RequireAuth fallback={<ShellSkeleton />}>
      <AppShell>
        <Suspense fallback={<Skeleton className="h-16 w-full" />}>
          <Switch>
            <Route path="/" component={Home} />
            <Route path="/credit/" component={CreditHistory} />
            <Route path="/session/" component={SessionDetail} />
            <Route path="/sessions/" component={AllSessions} />
            <Route path="/book/" component={Book} />
          </Switch>
        </Suspense>
      </AppShell>
    </RequireAuth>
  );
}

export function App() {
  const [path, navigate] = useLocation();

  // Every screen's URL ends in a slash; /credit becomes /credit/, as a static host would redirect it.
  useEffect(() => {
    if (!path.endsWith("/")) {
      navigate(`${path}/${window.location.search}`, { replace: true });
    }
  }, [path, navigate]);

  useEffect(() => afterFirstScreen(() => Object.values(screens).forEach(({ load }) => void load())), []);

  return (
    <Switch>
      <Route path="/sign-in/">
        <SignedOutScreen>
          <SignIn />
        </SignedOutScreen>
      </Route>
      <Route path="/sign-in/finish/">
        <SignedOutScreen>
          <FinishSignIn />
        </SignedOutScreen>
      </Route>
      <Route path="/welcome/">
        <SignedOutScreen>
          <Welcome />
        </SignedOutScreen>
      </Route>
      <Route path={SIGNED_IN}>
        <SignedInArea />
      </Route>
      <Route>
        <NotFound />
      </Route>
    </Switch>
  );
}
