import { useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { useEffect, useRef, useState } from "react";
import { signIn } from "@/auth";
import { Banner } from "@/components/ui/Banner";
import { buttonClasses } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { destinationAfterSignIn } from "@/features/after-sign-in";
import { LazyEmailForm } from "./LazyEmailForm";
import { signInMessage } from "./messages";
import { useTitle } from "@/lib/title";
import { SignInFrame } from "./SignInFrame";

type Step = { kind: "working" } | { kind: "needs-email" } | { kind: "failed"; message: string };

/**
 * Where a sign-in link (or the local sign-in server's redirect) lands. Opened on another device, it asks which
 * address the link was sent to, then signs in there.
 */
export function FinishSignIn() {
  const [, navigate] = useLocation();
  useTitle("Signing in");
  const client = useQueryClient();
  const [step, setStep] = useState<Step>({ kind: "working" });
  const started = useRef(false);

  const finish = async (email: string | null) => {
    try {
      const url = window.location.href;
      if (!(await signIn.isReturn(url))) {
        navigate("/sign-in/", { replace: true });
        return;
      }
      const result = await signIn.complete(url, email);
      if (result.kind === "needs-email") {
        setStep({ kind: "needs-email" });
        return;
      }
      navigate(await destinationAfterSignIn(client, null), { replace: true });
    } catch (failure) {
      setStep({ kind: "failed", message: signInMessage(failure) ?? "Something went wrong signing you in." });
    }
  };

  useEffect(() => {
    // Strict mode mounts twice in development; a link must only be used once.
    if (!started.current) {
      started.current = true;
      void finish(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (step.kind === "needs-email") {
    return (
      <SignInFrame title="Confirm your email">
        <p className="mb-8 text-body text-text-muted">
          You opened the link on a different device or browser. Enter the address it was sent to.
        </p>
        <LazyEmailForm label="Email" submit="Sign in" onSubmit={({ email }) => finish(email)} />
      </SignInFrame>
    );
  }

  if (step.kind === "failed") {
    return (
      <SignInFrame title="That link didn't work">
        <div className="flex flex-col gap-6">
          <Banner tone="danger">{step.message}</Banner>
          <Link href="/sign-in/" className={buttonClasses("primary", true, true)}>
            Send a new link
          </Link>
        </div>
      </SignInFrame>
    );
  }

  return (
    <SignInFrame title="Signing you in">
      <p className="sr-only" role="status">
        Signing you in
      </p>
      <Skeleton className="h-touch w-full" />
    </SignInFrame>
  );
}
