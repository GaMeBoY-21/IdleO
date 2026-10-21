import { useEffect } from "react";
import { Link, useSearch } from "wouter";
import { useCancelByLink } from "@/api/privacy";
import { Skeleton } from "@/components/ui/Skeleton";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { problemMessage } from "@/lib/problems";
import { useTitle } from "@/lib/title";

/**
 * The link in "Your account will be deleted" (ADR 0037): one click cancels, signed in or not. Opening it is the
 * click; the link's token is the proof, and nothing else is asked for.
 */
export function CancelDeletion() {
  useTitle("Keep my account");
  const params = new URLSearchParams(useSearch());
  const requestId = params.get("request") ?? "";
  const token = params.get("token") ?? "";
  const cancel = useCancelByLink();
  const { mutate } = cancel;

  useEffect(() => {
    if (requestId !== "" && token !== "") {
      mutate({ requestId, token });
    }
  }, [mutate, requestId, token]);

  return (
    <SignInFrame title={cancel.isSuccess ? "Your account stays" : "Keep my account"}>
      {cancel.isSuccess ? (
        <div className="flex flex-col gap-4">
          <p className="text-body">We won&apos;t delete your account. Everything stays as it was.</p>
          <Link href="/" className="text-small underline">
            Go to IdleO
          </Link>
        </div>
      ) : cancel.isError || requestId === "" || token === "" ? (
        <div className="flex flex-col gap-4">
          <p role="alert" className="text-body">
            {cancel.isError
              ? problemMessage(cancel.error)
              : "That link isn't complete. Open it again from the email, or cancel in Account."}
          </p>
          <Link href="/account/delete/" className="text-small underline">
            Cancel in Account
          </Link>
        </div>
      ) : (
        <div className="flex flex-col gap-4" role="status" aria-label="Cancelling">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-4.5 w-1/3" />
        </div>
      )}
    </SignInFrame>
  );
}
