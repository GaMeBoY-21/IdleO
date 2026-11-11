import { lazy, Suspense, useEffect, type ComponentType, type ReactNode } from "react";
import { useLocation } from "wouter";
import { Skeleton } from "@/components/ui/Skeleton";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { afterFirstScreen } from "@/lib/after-first-screen";

/**
 * What every site of the web app is drawn with (ADR 0058), the admin site's too: a screen in its own chunk, the
 * signed-out frame, signing in, and the rules every address follows. The renter app and the host site add their shared
 * frame (src/sites/frame.tsx); the admin site has the console's own (src/sites/admin/routes.tsx).
 */

export interface Screen {
  load: () => Promise<unknown>;
  Screen: ComponentType;
}

/**
 * A screen in its own chunk. Once its code has arrived it's drawn directly; before that, through React.lazy. React
 * holds back a suspended screen's first paint by up to 300 ms even when its code is already there, so the screen a
 * page opens on is loaded before the first render (preloadScreen, from src/main.tsx) and never suspends.
 */
export function screen(load: () => Promise<ComponentType>): Screen {
  let loaded: ComponentType | null = null;
  const fetch = () =>
    load().then((component) => {
      loaded = component;
      return component;
    });
  const Lazy = lazy(() => fetch().then((component) => ({ default: component })));
  const Screen = () => {
    const Component = loaded ?? Lazy;
    return <Component />;
  };
  return { load: fetch, Screen };
}

/** Signing in and what may come straight after it (the Terms, an invite code): every site has these, at these paths. */
export const SIGN_IN_SCREENS = {
  "/sign-in/": screen(() => import("@/features/sign-in/SignIn").then((m) => m.SignIn)),
  "/sign-in/finish/": screen(() => import("@/features/sign-in/FinishSignIn").then((m) => m.FinishSignIn)),
  "/agree/": screen(() => import("@/features/agree/Agree").then((m) => m.Agree)),
  "/invite/": screen(() => import("@/features/invite/Invite").then((m) => m.Invite)),
} satisfies Record<string, Screen>;

/**
 * Every site's addresses: each ends in a slash (/credit becomes /credit/, as a static host would redirect it), and every
 * screen's code is fetched once the first screen has loaded, so moving between screens is instant.
 */
export function useSiteAddresses(screens: readonly Screen[]): void {
  const [path, navigate] = useLocation();
  useEffect(() => {
    if (!path.endsWith("/")) {
      navigate(`${path}/${window.location.search}`, { replace: true });
    }
  }, [path, navigate]);
  useEffect(() => afterFirstScreen(() => screens.forEach(({ load }) => void load())), [screens]);
}

/** A signed-out screen's frame, with its own placeholder while its code arrives; never a site's bars. */
export function SignedOutScreen({ children }: { children: ReactNode }) {
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
