import { Check } from "lucide-react";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { ApiError } from "@/api/errors";
import { useApproveCliLogin, useCliLogin } from "@/api/keys";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Banner } from "@/components/ui/Banner";
import { Button, buttonClasses } from "@/components/ui/Button";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { problemMessage } from "@/lib/problems";
import { useTitle } from "@/lib/title";

/**
 * Where `idleo login` sends you (ADR 0025, shaped like RFC 8628): "Sign in the CLI on this computer?", with the code
 * to compare against the terminal's, Approve and Cancel. Approving hands the waiting CLI a new key; it never goes
 * through this page. A sign-in that has expired or been used says so, and what to do.
 */
export function CliLogin() {
  useTitle("Sign in the command line");
  // Blank until sign-in is restored: a card with only its title would grow when the rest arrives.
  return (
    <RequireAuth fallback={<div className="min-h-dvh" />}>
      <Approval />
    </RequireAuth>
  );
}

function Approval() {
  const code = new URLSearchParams(useSearch()).get("code");
  const login = useCliLogin(code);
  const approve = useApproveCliLogin();
  const [cancelled, setCancelled] = useState(false);

  if (approve.isSuccess) {
    return (
      <SignInFrame title="Signed in">
        <div className="flex flex-col gap-4">
          <Check aria-hidden="true" className="size-8 text-running" strokeWidth={1.75} />
          <p className="text-body">You can close this tab. The command line has what it needs.</p>
        </div>
      </SignInFrame>
    );
  }
  if (cancelled) {
    return (
      <SignInFrame title="Not signed in">
        <div className="flex flex-col gap-6">
          <p className="text-body text-text-2">
            Nothing was approved. The sign-in expires by itself in a few minutes.
          </p>
          <Link href="/" className={buttonClasses("ghost", true, true)}>
            Back to IdleO
          </Link>
        </div>
      </SignInFrame>
    );
  }
  if (code === null || code === "") {
    return (
      <SignInFrame title="Sign in the command line">
        <p className="text-body text-text-2">
          Run <span className="font-semibold">idleo login</span> in a terminal and open the link it prints.
        </p>
      </SignInFrame>
    );
  }
  if (login.isError) {
    const gone = login.error instanceof ApiError && login.error.status === 404;
    return (
      <SignInFrame title={gone ? "That sign-in has ended" : "We couldn't check that sign-in"}>
        <div className="flex flex-col gap-4">
          <p className="text-body text-text-2">
            {gone
              ? "It has expired, was already approved, or the code is wrong. Run idleo login again for a new one."
              : problemMessage(login.error)}
          </p>
          {!gone && (
            <Button variant="ghost" large block onClick={() => void login.refetch()} busy={login.isFetching}>
              Try again
            </Button>
          )}
        </div>
      </SignInFrame>
    );
  }
  // The page is drawn whole while the sign-in is looked up: its code is in the link, so only the device's name is
  // still to come, and nothing moves when it arrives.
  const device = login.data?.deviceName;
  return (
    <SignInFrame title="Sign in the command line?">
      <div className="flex flex-col gap-6" aria-busy={device === undefined}>
        <div className="flex flex-col gap-1">
          <p className="text-body text-text-2">
            A command line is asking to sign in as you. Check that this is the code your terminal shows. If it
            isn&apos;t, don&apos;t approve it.
          </p>
          {device === undefined ? (
            <div role="status" aria-label="Loading the sign-in">
              <SkeletonLine size="body" width="w-1/2" />
            </div>
          ) : (
            <p className="truncate text-body font-semibold" title={device}>
              On {device}
            </p>
          )}
        </div>
        <p className="rounded-control bg-panel-2 p-5 text-title tabular" aria-label="The code to compare">
          {code.toUpperCase()}
        </p>
        {approve.isError && <Banner tone="danger">{problemMessage(approve.error)}</Banner>}
        <div className="flex flex-col gap-2">
          <Button
            large
            block
            disabled={login.data === undefined}
            busy={approve.isPending}
            onClick={() => login.data && approve.mutate(login.data.id)}
          >
            Approve
          </Button>
          <Button variant="ghost" large block disabled={approve.isPending} onClick={() => setCancelled(true)}>
            Cancel
          </Button>
        </div>
      </div>
    </SignInFrame>
  );
}
