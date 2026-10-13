import { useQueryClient } from "@tanstack/react-query";
import { MailCheck } from "lucide-react";
import { useLocation, useSearch } from "wouter";
import { useEffect, useState } from "react";
import { authProvider, signIn, useAuth } from "@/auth";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { destinationAfterSignIn } from "@/features/after-sign-in";
import { LazyEmailForm } from "./LazyEmailForm";
import { GoogleMark } from "./GoogleMark";
import { signInMessage } from "./messages";
import { SignInFrame } from "./SignInFrame";
import { useTitle } from "@/lib/title";

/**
 * Sign in with Google, or with a link sent by email (ADR 0009). The email link's primary button is the one next
 * step; Google keeps Google's own button style.
 */
export function SignIn() {
  const auth = useAuth();
  const [, navigate] = useLocation();
  const client = useQueryClient();
  const search = new URLSearchParams(useSearch());
  const next = search.get("next");
  useTitle("Sign in");
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [google, setGoogle] = useState(false);

  // Already signed in (or just signed in with Google): on to the app.
  useEffect(() => {
    if (auth.status === "signed-in") {
      void destinationAfterSignIn(client, next).then((to) => navigate(to, { replace: true }));
    }
  }, [auth.status, client, next, navigate]);

  const withGoogle = async () => {
    setError(null);
    setGoogle(true);
    try {
      await signIn.withGoogle();
    } catch (failure) {
      setError(signInMessage(failure));
      setGoogle(false);
    }
  };

  if (sentTo) {
    return (
      <SignInFrame title="Check your email">
        <div className="flex flex-col gap-4">
          <MailCheck aria-hidden="true" className="size-8 text-running" strokeWidth={1.75} />
          <p className="text-body">
            We sent a sign-in link to <strong className="font-semibold break-all">{sentTo}</strong>.
          </p>
          <p className="text-small text-text-muted">
            Open it on this device or another one. It works once, within an hour. If it hasn&apos;t arrived in
            a minute, check your spam folder.
          </p>
          <Button variant="secondary" large block onClick={() => setSentTo(null)}>
            Use a different email
          </Button>
        </div>
      </SignInFrame>
    );
  }

  return (
    <SignInFrame title="Sign in">
      <p className="mb-8 text-body text-text-muted">Rent a GPU by the minute, paid in rupees.</p>
      <div className="flex flex-col gap-6">
        {search.get("expired") && (
          <Banner tone="warning">Your sign-in has expired. Sign in again to carry on.</Banner>
        )}
        {authProvider === "oidc-mock" && (
          <Banner tone="info">
            Local sign-in: any @example.com address signs in at once, and no email is sent.
          </Banner>
        )}
        {error && <Banner tone="danger">{error}</Banner>}
        <Button variant="secondary" large block busy={google} onClick={() => void withGoogle()}>
          {!google && <GoogleMark />}
          Continue with Google
        </Button>
        <div className="flex items-center gap-3 text-caption text-text-muted" aria-hidden="true">
          <span className="h-px flex-1 bg-divider" />
          or
          <span className="h-px flex-1 bg-divider" />
        </div>
        <LazyEmailForm
          label="Email"
          submit="Email me a sign-in link"
          onSubmit={async ({ email }) => {
            setError(null);
            try {
              const result = await signIn.withEmailLink(email);
              if (result.kind === "sent") {
                setSentTo(email);
              }
            } catch (failure) {
              setError(signInMessage(failure));
            }
          }}
        />
        <p className="text-caption text-text-muted">
          By continuing you agree to the{" "}
          <a href="/terms/" className="underline">
            terms
          </a>{" "}
          and have read the{" "}
          <a href="/privacy/" className="underline">
            privacy notice
          </a>
          .
        </p>
      </div>
    </SignInFrame>
  );
}
