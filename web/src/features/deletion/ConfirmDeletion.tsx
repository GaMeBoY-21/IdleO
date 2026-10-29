import { Link, useSearch } from "wouter";
import { useConfirmDeletion, useDeletion } from "@/api/privacy";
import { RequireAuth } from "@/components/shell/RequireAuth";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { problemMessage } from "@/lib/problems";
import { formatDay } from "@/lib/time";
import { useTitle } from "@/lib/title";

/**
 * The link in "Confirm deleting your account" (ADR 0037): signed in, the person confirms with one deliberate press,
 * never by just opening the link (a mail scanner opens links too). Then the wait begins, and the page says until when.
 */
export function ConfirmDeletion() {
  useTitle("Confirm deleting your account");
  // Blank until sign-in is restored: a card with only its title would grow when the rest arrives.
  return (
    <RequireAuth fallback={<div className="min-h-dvh" />}>
      <ConfirmStep />
    </RequireAuth>
  );
}

function ConfirmStep() {
  const params = new URLSearchParams(useSearch());
  const requestId = params.get("request") ?? "";
  const token = params.get("token") ?? "";
  const standing = useDeletion();
  const confirm = useConfirmDeletion();
  const scheduled = standing.data?.request?.state === "scheduled" ? standing.data.request : null;

  return (
    <SignInFrame title={scheduled ? "Your account will be deleted" : "Confirm deleting your account"}>
      {scheduled?.deleteAfter ? (
        <div className="flex flex-col gap-4">
          <p className="text-body">
            On {formatDay(scheduled.deleteAfter)} we delete your account. Until then you can cancel, in
            Account or with the link in the email we just sent.
          </p>
          <Link href="/account/delete/" className="text-small underline">
            Back to your account
          </Link>
        </div>
      ) : standing.isPending ? (
        <div className="flex flex-col gap-4" role="status" aria-label="Loading">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-touch w-full" />
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-body text-text-2">
            Once you confirm, we wait {standing.data?.waitingDays ?? 7} days, then delete your account. You
            can cancel any time until then.
          </p>
          <Button
            variant="danger"
            large
            block
            busy={confirm.isPending}
            disabled={requestId === "" || token === ""}
            onClick={() => confirm.mutate({ requestId, token })}
          >
            Confirm: delete my account
          </Button>
          <p role="alert" className="min-h-4.5 text-caption text-danger">
            {confirm.isError ? problemMessage(confirm.error) : undefined}
          </p>
          <Link href="/" className="text-small underline">
            Keep my account
          </Link>
        </div>
      )}
    </SignInFrame>
  );
}
