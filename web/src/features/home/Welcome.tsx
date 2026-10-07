import { useLocation } from "wouter";
import { useMe, useWallet } from "@/api/queries";
import { brand } from "@/brand/brand";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Skeleton } from "@/components/ui/Skeleton";
import { markWelcomed } from "@/features/after-sign-in";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { useTitle } from "@/lib/title";

/** The first screen of a new account: what this is, what credit they have, and one way on. Never a form. */
export function Welcome() {
  useTitle("Welcome");
  return (
    <RequireAuth fallback={<SignInFrame title={`Welcome to ${brand.name}`}>{null}</SignInFrame>}>
      <WelcomeCard />
    </RequireAuth>
  );
}

function WelcomeCard() {
  const [, navigate] = useLocation();
  const me = useMe();
  const wallet = useWallet();
  const available = wallet.data?.availablePaise ?? 0;

  return (
    <SignInFrame title={`Welcome to ${brand.name}`}>
      <div className="flex flex-col gap-6">
        <p className="text-body text-text-muted">
          Run your code on someone&apos;s idle gaming GPU, by the minute, paid in rupees. You&apos;re only
          charged for the minutes your session runs.
        </p>
        <div className="min-h-12 text-body">
          {wallet.isPending ? (
            <Skeleton className="h-6 w-3/4" />
          ) : available > 0 ? (
            <p>
              You have <Money paise={available} className="font-semibold" /> of credit to start with.
            </p>
          ) : (
            <p className="text-text-muted">Your credit will show on your home screen.</p>
          )}
        </div>
        <Button
          block
          onClick={() => {
            if (me.data) {
              markWelcomed(me.data.id);
            }
            navigate("/", { replace: true });
          }}
        >
          Go to your home
        </Button>
      </div>
    </SignInFrame>
  );
}
