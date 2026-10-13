import { lazy, Suspense, useEffect, type ComponentType, type ReactNode } from "react";
import { Route, Switch, useLocation } from "wouter";
import { AppShell, ShellSkeleton } from "@/components/shell/AppShell";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Skeleton } from "@/components/ui/Skeleton";
import { Home } from "@/features/home/Home";
import { CliLogin } from "@/features/cli-login/CliLogin";
import { Invite } from "@/features/invite/Invite";
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
  "/files/": screen(() => import("@/features/files/MyFiles").then((m) => m.MyFiles)),
  "/hosting/": screen(() => import("@/features/hosting/Hosting").then((m) => m.Hosting)),
  "/hosting/machine/": screen(() => import("@/features/hosting/Machine").then((m) => m.Machine)),
  "/hosting/price/": screen(() => import("@/features/hosting/Price").then((m) => m.Price)),
  "/hosting/sessions/": screen(() => import("@/features/hosting/HostSessions").then((m) => m.HostSessions)),
  "/hosting/earnings/": screen(() => import("@/features/hosting/Earnings").then((m) => m.Earnings)),
  "/hosting/add/": screen(() => import("@/features/hosting/AddMachine").then((m) => m.AddMachine)),
  "/sign-in/finish/": screen(() => import("@/features/sign-in/FinishSignIn").then((m) => m.FinishSignIn)),
  "/welcome/": screen(() => import("@/features/home/Welcome").then((m) => m.Welcome)),
  "/account/": screen(() => import("@/features/account/Account").then((m) => m.Account)),
  "/account/keys/": screen(() => import("@/features/account/ApiKeys").then((m) => m.ApiKeys)),
  "/account/reports/": screen(() => import("@/features/account/Reports").then((m) => m.Reports)),
  "/notifications/": screen(() =>
    import("@/features/notifications/Notifications").then((m) => m.Notifications),
  ),
};

/**
 * The admin console (ADR 0027): one chunk of its own, loaded only on an /admin/ address, and never with the other
 * screens after the first one, so a renter never downloads it.
 */
const admin = screen(() => import("@/features/admin/AdminConsole").then((m) => m.AdminConsole));
const ADMIN_PATH = /^\/admin\//;

/** Loads the code of the screen at `path`, if it has its own; resolves at once for the others. */
export function preloadScreen(path: string): Promise<unknown> {
  const own = ADMIN_PATH.test(path)
    ? admin
    : (screens as Record<string, { load: () => Promise<unknown> } | undefined>)[path];
  return own ? own.load().catch(() => undefined) : Promise.resolve();
}

const CreditHistory = screens["/credit/"].Screen;
const AllSessions = screens["/sessions/"].Screen;
const SessionDetail = screens["/session/"].Screen;
const Book = screens["/book/"].Screen;
const MyFiles = screens["/files/"].Screen;
const Hosting = screens["/hosting/"].Screen;
const Machine = screens["/hosting/machine/"].Screen;
const Price = screens["/hosting/price/"].Screen;
const HostSessions = screens["/hosting/sessions/"].Screen;
const Earnings = screens["/hosting/earnings/"].Screen;
const AddMachine = screens["/hosting/add/"].Screen;
const FinishSignIn = screens["/sign-in/finish/"].Screen;
const Welcome = screens["/welcome/"].Screen;
const Account = screens["/account/"].Screen;
const ApiKeys = screens["/account/keys/"].Screen;
const Reports = screens["/account/reports/"].Screen;
const Notifications = screens["/notifications/"].Screen;

/** The signed-in screens' paths: one frame around all of them, which stays put while the content changes. */
const SIGNED_IN =
  /^\/(credit\/|session\/|sessions\/|book\/|files\/|account\/(keys\/|reports\/)?|notifications\/|hosting\/(machine\/|price\/|sessions\/|earnings\/|add\/)?)?$/;

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
            <Route path="/files/" component={MyFiles} />
            <Route path="/account/" component={Account} />
            <Route path="/account/keys/" component={ApiKeys} />
            <Route path="/account/reports/" component={Reports} />
            <Route path="/notifications/" component={Notifications} />
            <Route path="/hosting/" component={Hosting} />
            <Route path="/hosting/machine/" component={Machine} />
            <Route path="/hosting/price/" component={Price} />
            <Route path="/hosting/sessions/" component={HostSessions} />
            <Route path="/hosting/earnings/" component={Earnings} />
            <Route path="/hosting/add/" component={AddMachine} />
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
      {/* In the first load, not their own chunks: small, and drawn whole on the server, so a page opened on one
          hydrates without a Suspense boundary to rebuild. */}
      <Route path="/invite/" component={Invite} />
      <Route path="/cli-login/" component={CliLogin} />
      {/* Signed in first, so the pre-rendered frame is this empty one, never the console's chunk. */}
      <Route path={ADMIN_PATH}>
        <RequireAuth fallback={<div className="min-h-dvh" />}>
          <Suspense fallback={<div className="min-h-dvh" />}>
            <admin.Screen />
          </Suspense>
        </RequireAuth>
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
