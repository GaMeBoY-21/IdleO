import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useState } from "react";
import { useLocation, useSearch } from "wouter";
import { useAcceptNotices } from "@/api/account";
import { useMe } from "@/api/queries";
import { signOut, useAuth } from "@/auth";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { destinationAfterSignIn } from "@/features/after-sign-in";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { problemMessage } from "@/lib/problems";
import { siteLink } from "@/lib/site-links";
import { useTitle } from "@/lib/title";

/**
 * The Terms and the privacy notice (ADR 0037), before anything else: on a first sign-in, with "I'm 18 or older", and
 * again, once, whenever either changes. Agreeing is recorded by the server; the person then carries on where they
 * were going.
 */
export function Agree() {
  useTitle("Before you start");
  return (
    <RequireAuth fallback={<SignInFrame title="Before you start">{null}</SignInFrame>}>
      <AgreeStep />
    </RequireAuth>
  );
}

function AgreeStep() {
  const auth = useAuth();
  const client = useQueryClient();
  const [, navigate] = useLocation();
  const next = new URLSearchParams(useSearch()).get("next");
  const me = useMe();
  const accept = useAcceptNotices();
  const [agreed, setAgreed] = useState(false);
  const [adult, setAdult] = useState(false);
  const [missing, setMissing] = useState(false);
  const email = auth.status === "signed-in" ? auth.user.email : null;
  const notices = me.data?.notices;
  const askAge = notices ? !notices.ageConfirmed : true;
  const changed = notices ? notices.ageConfirmed && !notices.accepted : false;

  const leave = () => void destinationAfterSignIn(client, next).then((to) => navigate(to, { replace: true }));

  // Already agreed (a bookmark, or the back button): nothing to do here.
  useEffect(() => {
    if (notices?.accepted) {
      leave();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notices?.accepted]);

  const submit = () => {
    if (!notices) {
      return;
    }
    if (!agreed || (askAge && !adult)) {
      setMissing(true);
      return;
    }
    setMissing(false);
    accept.mutate(
      { termsVersion: notices.termsVersion, privacyVersion: notices.privacyVersion, ageConfirmed: adult },
      { onSuccess: leave },
    );
  };

  const error = missing
    ? askAge
      ? `Tick both boxes to carry on: the Terms and that you're ${notices?.minimumAge ?? 18} or older.`
      : "Tick the box to carry on."
    : accept.isError
      ? problemMessage(accept.error)
      : undefined;

  return (
    <SignInFrame title="Before you start">
      <p className="mb-8 text-body text-text-2">
        {changed
          ? "Our Terms or Privacy notice changed since you last agreed. Read them, then agree to carry on."
          : "Read our Terms and Privacy notice. You need to agree to them to use IdleO."}
      </p>
      {me.isPending ? (
        <div className="flex flex-col gap-4" role="status" aria-label="Loading">
          <Skeleton className="h-touch w-full" />
          <Skeleton className="h-touch w-full" />
          <div className="h-4.5" />
          <Skeleton className="h-touch w-full" />
        </div>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <Tick checked={agreed} onChange={setAgreed}>
            I agree to the{" "}
            <a className="underline" href={siteLink("/terms/")} target="_blank" rel="noreferrer">
              Terms
            </a>{" "}
            and the{" "}
            <a className="underline" href={siteLink("/privacy/")} target="_blank" rel="noreferrer">
              Privacy notice
            </a>
          </Tick>
          {askAge ? (
            <Tick checked={adult} onChange={setAdult}>
              I&apos;m {notices?.minimumAge ?? 18} or older
            </Tick>
          ) : (
            <div className="h-touch" aria-hidden="true" />
          )}
          <p role="alert" className="min-h-4.5 text-caption text-danger">
            {error}
          </p>
          <Button type="submit" large block busy={accept.isPending}>
            Agree and continue
          </Button>
        </form>
      )}
      <p className="mt-6 text-caption text-text-2">
        {email ? `Signed in as ${email}. ` : ""}
        <button type="button" className="underline" onClick={() => void signOut()}>
          Sign out
        </button>
      </p>
    </SignInFrame>
  );
}

function Tick({
  checked,
  onChange,
  children,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  children: React.ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex min-h-touch items-center gap-3">
      <input
        id={id}
        type="checkbox"
        className="size-5 shrink-0 cursor-pointer accent-primary"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      <label htmlFor={id} className="cursor-pointer text-body">
        {children}
      </label>
    </div>
  );
}
