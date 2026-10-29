import { useLocation } from "wouter";
import { useMe, useWallet } from "@/api/queries";
import { brand } from "@/brand/brand";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { markWelcomed } from "@/features/after-sign-in";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { useTitle } from "@/lib/title";

/** The first screen of a new account: what this is, the credit they start with, and one way on. Never a form. */
export function Welcome() {
  useTitle("Welcome");
  // Blank until sign-in is restored: a card with only its title would grow when the rest arrives.
  return (
    <RequireAuth fallback={<div className="min-h-dvh" />}>
      <WelcomeCard />
    </RequireAuth>
  );
}

const STEPS = [
  { title: "Say what to run", detail: "Fine-tune a model on your data, run your code, or a ready-made job." },
  { title: "Give it your data", detail: "Upload a file from your laptop, or give a link to one." },
  {
    title: "Request a GPU",
    detail: "We match the best free GPU for the job. Watch it run, then download the outputs.",
  },
];

function WelcomeCard() {
  const [, navigate] = useLocation();
  const me = useMe();
  const wallet = useWallet();
  const leave = (to: string) => {
    if (me.data) {
      markWelcomed(me.data.id);
    }
    navigate(to, { replace: true });
  };

  return (
    <SignInFrame title={`Welcome to ${brand.name}`}>
      <div className="flex flex-col gap-7">
        <p className="text-body text-text-2">
          Run your code on someone&apos;s idle gaming GPU, by the minute, paid in rupees. You&apos;re only
          charged for the minutes your session runs.
        </p>
        <div className="min-h-6 text-option">
          {wallet.data ? (
            wallet.data.availablePaise > 0 ? (
              <p>
                You have <Money paise={wallet.data.availablePaise} className="font-semibold" /> of credit to
                start with.
              </p>
            ) : (
              <p className="text-text-2">Your credit will show on your home screen.</p>
            )
          ) : (
            <SkeletonLine size="option" width="w-3/4" />
          )}
        </div>
        <ol className="flex flex-col gap-4">
          {STEPS.map((step, index) => (
            <li key={step.title} className="flex gap-3">
              <span
                aria-hidden="true"
                className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary text-small font-semibold text-primary-fg"
              >
                {index + 1}
              </span>
              <div>
                <p className="text-option font-semibold">{step.title}</p>
                <p className="text-small text-text-2">{step.detail}</p>
              </div>
            </li>
          ))}
        </ol>
        <div className="flex flex-col gap-2">
          <Button block large onClick={() => leave("/book/")}>
            Choose what to run
          </Button>
          <Button block large variant="quiet" onClick={() => leave("/")}>
            Go to your home
          </Button>
        </div>
      </div>
    </SignInFrame>
  );
}
