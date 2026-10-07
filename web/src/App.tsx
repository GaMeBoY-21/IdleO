import { lazy, Suspense, useEffect, type ReactNode } from "react";
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
const screens = {
  credit: () => import("@/features/credit/CreditHistory"),
  session: () => import("@/features/session/SessionDetail"),
  book: () => import("@/pages/BookPage"),
  finishSignIn: () => import("@/features/sign-in/FinishSignIn"),
  welcome: () => import("@/features/home/Welcome"),
};

const CreditHistory = lazy(() => screens.credit().then((module) => ({ default: module.CreditHistory })));
const SessionDetail = lazy(() => screens.session().then((module) => ({ default: module.SessionDetail })));
const BookPage = lazy(() => screens.book().then((module) => ({ default: module.BookPage })));
const FinishSignIn = lazy(() => screens.finishSignIn().then((module) => ({ default: module.FinishSignIn })));
const Welcome = lazy(() => screens.welcome().then((module) => ({ default: module.Welcome })));

/** The signed-in screens' paths: one frame around all of them, which stays put while the content changes. */
const SIGNED_IN = /^\/(credit\/|session\/|book\/)?$/;

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
            <Route path="/book/" component={BookPage} />
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

  useEffect(() => afterFirstScreen(() => Object.values(screens).forEach((load) => void load())), []);

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
