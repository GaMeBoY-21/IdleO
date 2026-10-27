import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useLocation, useSearch } from "wouter";
import { useActivate } from "@/api/account";
import { useMe } from "@/api/queries";
import { signOut, useAuth } from "@/auth";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Skeleton } from "@/components/ui/Skeleton";
import { destinationAfterSignIn } from "@/features/after-sign-in";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { formatInviteCode, isCompleteInviteCode } from "@/lib/invite";
import { problemMessage } from "@/lib/problems";
import { useTitle } from "@/lib/title";

/**
 * The step after a first sign-in while access is by invitation (ADR 0024): one field for the code, typed in groups,
 * and one way on. An account that already has its code is sent on, so the page is never a dead end.
 */
export function Invite() {
  useTitle("Invite code");
  return (
    <RequireAuth fallback={<SignInFrame title="Enter your invite code">{null}</SignInFrame>}>
      <InviteStep />
    </RequireAuth>
  );
}

function InviteStep() {
  const auth = useAuth();
  const client = useQueryClient();
  const [, navigate] = useLocation();
  const next = new URLSearchParams(useSearch()).get("next");
  const me = useMe();
  const activate = useActivate();
  const [code, setCode] = useState("");
  const [short, setShort] = useState(false);
  const email = auth.status === "signed-in" ? auth.user.email : null;

  const leave = () => void destinationAfterSignIn(client, next).then((to) => navigate(to, { replace: true }));

  // Already active (a bookmark, or the back button): nothing to enter.
  useEffect(() => {
    if (me.data && me.data.status !== "waiting") {
      leave();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [me.data?.status]);

  const submit = () => {
    if (!isCompleteInviteCode(code)) {
      setShort(true);
      return;
    }
    setShort(false);
    activate.mutate(code, { onSuccess: leave });
  };

  const error = short
    ? "Enter all 8 letters and numbers of your code."
    : activate.isError
      ? problemMessage(activate.error)
      : undefined;

  return (
    <SignInFrame title="Enter your invite code">
      <p className="mb-8 text-body text-text-2">
        IdleO is open by invitation for now. Enter the code you were given to carry on.
      </p>
      {me.isPending ? (
        <div className="flex flex-col gap-2" role="status" aria-label="Loading">
          <Skeleton className="h-14 w-full" />
          <div className="h-4.5" />
          <Skeleton className="h-touch w-full" />
        </div>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <Field
            label="Invite code"
            value={code}
            placeholder="IDLE-XXXX-XXXX"
            autoComplete="off"
            autoCapitalize="characters"
            autoCorrect="off"
            spellCheck={false}
            error={error}
            onChange={(event) => {
              setCode(formatInviteCode(event.target.value));
              setShort(false);
              if (activate.isError) {
                activate.reset();
              }
            }}
          />
          <Button type="submit" large block busy={activate.isPending}>
            Continue
          </Button>
        </form>
      )}
      <p className="mt-6 text-small text-text-2">Don&apos;t have a code? Ask the person who invited you.</p>
      <p className="mt-3 text-caption text-text-2">
        {email ? `Signed in as ${email}. ` : ""}
        <button type="button" className="underline" onClick={() => void signOut()}>
          Sign out
        </button>
      </p>
    </SignInFrame>
  );
}
