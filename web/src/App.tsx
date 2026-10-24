import { lazy, Suspense, useEffect, type ComponentType, type ReactNode } from "react";
import { Route, Switch, useLocation, useSearch } from "wouter";
import { useMeAfterPaint } from "@/api/queries";
import { AppShell, ShellSkeleton } from "@/components/shell/AppShell";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Skeleton } from "@/components/ui/Skeleton";
import { Home } from "@/features/home/Home";
import { agreeStep } from "@/features/after-sign-in";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { afterFirstScreen } from "@/lib/after-first-screen";
import { NotFound } from "@/pages/NotFound";

/**
 * The app's screens (ADR 0018). Home, where a signed-in visit starts, is in the first load; every other screen is its
 * own chunk (sign-in, the CLI's and the invite step's too, so the signed-in first load doesn't carry them), fetched
 * once the first screen has loaded, so moving between screens is still instant. URLs end in a slash: every
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
  "/link/": screen(() => import("@/features/hosting/LinkApproval").then((m) => m.LinkApprovalScreen)),
  "/sign-in/": screen(() => import("@/features/sign-in/SignIn").then((m) => m.SignIn)),
  "/sign-in/finish/": screen(() => import("@/features/sign-in/FinishSignIn").then((m) => m.FinishSignIn)),
  "/welcome/": screen(() => import("@/features/home/Welcome").then((m) => m.Welcome)),
  "/agree/": screen(() => import("@/features/agree/Agree").then((m) => m.Agree)),
  "/account/": screen(() => import("@/features/account/Account").then((m) => m.Account)),
  "/account/keys/": screen(() => import("@/features/account/ApiKeys").then((m) => m.ApiKeys)),
  "/account/reports/": screen(() => import("@/features/account/Reports").then((m) => m.Reports)),
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
  "/invite/": screen(() => import("@/features/invite/Invite").then((m) => m.Invite)),
  "/cli-login/": screen(() => import("@/features/cli-login/CliLogin").then((m) => m.CliLogin)),
};

/**
 * The admin console (ADR 0027): one chunk of its own, loaded only on an /admin/ address, and never with the other
 * screens after the first one, so a renter never downloads it.
 */
const admin = screen(() => import("@/features/admin/AdminConsole").then((m) => m.AdminConsole));
const ADMIN_PATH = /^\/admin\//;

/** Loads the code of the screen at `path`, if it has its own; resolves at once for the others. */
export function preloadScreen(path: string): Promise<unknown> {
  // A link is at /link/<id>/: its screen is the one for /link/.
  path = path.replace(/^\/link\/[^/]+\/$/, "/link/");
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
const LinkApproval = screens["/link/"].Screen;
const SignIn = screens["/sign-in/"].Screen;
const FinishSignIn = screens["/sign-in/finish/"].Screen;
const Welcome = screens["/welcome/"].Screen;
const Agree = screens["/agree/"].Screen;
const Account = screens["/account/"].Screen;
const ApiKeys = screens["/account/keys/"].Screen;
const Reports = screens["/account/reports/"].Screen;
const YourData = screens["/account/data/"].Screen;
const DeleteAccount = screens["/account/delete/"].Screen;
const Help = screens["/account/help/"].Screen;
const HelpTicket = screens["/account/help/ticket/"].Screen;
const ConfirmDeletion = screens["/account/delete/confirm/"].Screen;
const CancelDeletion = screens["/account/delete/cancel/"].Screen;
const Notifications = screens["/notifications/"].Screen;
const Invite = screens["/invite/"].Screen;
const CliLogin = screens["/cli-login/"].Screen;

/** The signed-in screens' paths: one frame around all of them, which stays put while the content changes. */
const SIGNED_IN =
  /^\/(credit\/|session\/|sessions\/|book\/|files\/|account\/(keys\/|reports\/|data\/|delete\/|help\/(ticket\/)?)?|notifications\/|link\/([^/]+\/)?|hosting\/(machine\/|price\/|sessions\/|earnings\/|add\/)?)?$/;

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

/**
 * The Terms or privacy notice changed since this person agreed (ADR 0037): they're asked once, before anything else,
 * as soon as the account says so: at once from what the screen already has, else once the screen has painted.
 */
function NoticesGate() {
  const me = useMeAfterPaint();
  const [path, navigate] = useLocation();
  const search = useSearch();
  const pending = me.data !== undefined && !me.data.notices.accepted;
  useEffect(() => {
    if (pending) {
      navigate(agreeStep(`${path}${search ? `?${search}` : ""}`), { replace: true });
    }
  }, [pending, path, search, navigate]);
  return null;
}

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
          <Route path="/account/data/" component={YourData} />
          <Route path="/account/delete/" component={DeleteAccount} />
          <Route path="/account/help/" component={Help} />
          <Route path="/account/help/ticket/" component={HelpTicket} />
          <Route component={HeldAccount} />
        </Switch>
      </Suspense>
    </HeldShell>
  );
}

function SignedInArea() {
  const me = useMeAfterPaint();
  if (me.data?.status === "suspended") {
    return (
      <RequireAuth fallback={<ShellSkeleton />}>
        <Suspense fallback={<ShellSkeleton />}>
          <HeldArea />
        </Suspense>
      </RequireAuth>
    );
  }
  return (
    <RequireAuth fallback={<ShellSkeleton />}>
      <NoticesGate />
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
            <Route path="/account/data/" component={YourData} />
            <Route path="/account/delete/" component={DeleteAccount} />
            <Route path="/account/help/" component={Help} />
            <Route path="/account/help/ticket/" component={HelpTicket} />
            <Route path="/notifications/" component={Notifications} />
            <Route path="/hosting/" component={Hosting} />
            <Route path="/hosting/machine/" component={Machine} />
            <Route path="/hosting/price/" component={Price} />
            <Route path="/hosting/sessions/" component={HostSessions} />
            <Route path="/hosting/earnings/" component={Earnings} />
            <Route path="/hosting/add/" component={AddMachine} />
            <Route path="/link/:id/" component={LinkApproval} />
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
      <Route path="/account/delete/confirm/">
        <SignedOutScreen>
          <ConfirmDeletion />
        </SignedOutScreen>
      </Route>
      <Route path="/account/delete/cancel/">
        <SignedOutScreen>
          <CancelDeletion />
        </SignedOutScreen>
      </Route>
      <Route path="/agree/">
        <SignedOutScreen>
          <Agree />
        </SignedOutScreen>
      </Route>
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
