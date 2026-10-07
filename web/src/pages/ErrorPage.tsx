import { Button } from "@/components/ui/Button";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { useTitle } from "@/lib/title";

/** Something broke while showing a screen. The details go to the console, never to the screen. */
export function ErrorPage({ reset }: { reset: () => void }) {
  useTitle("Something went wrong");
  return (
    <SignInFrame title="Something went wrong">
      <p className="mb-6 text-small text-text-muted">
        This page hit a problem it couldn&apos;t recover from. Try again; if it keeps happening, reload the
        page.
      </p>
      <Button block onClick={reset}>
        Try again
      </Button>
    </SignInFrame>
  );
}
