import { lazy, Suspense, useState } from "react";
import { Link } from "wouter";
import { ChevronLeft } from "lucide-react";
import { useApiKeys, useRevokeKey, type ApiKey } from "@/api/keys";
import { Page } from "@/components/shell/AppShell";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { SectionTitle } from "@/components/ui/Card";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { problemMessage } from "@/lib/problems";
import { useAfterPaint } from "@/lib/after-first-screen";
import { useTitle } from "@/lib/title";
import { CreateKey } from "./CreateKey";

// The sheet (Radix) isn't needed until Revoke is pressed, and loading it with the screen would count against its
// speed budget (ADR 0018).
const RevokeDialog = lazy(() =>
  import("./RevokeDialog").then((module) => ({ default: module.RevokeDialog })),
);

const ROW = "flex min-h-18 items-center gap-3 py-3";
const SCOPES: Record<ApiKey["scope"], string> = { full: "Full access", read: "Read only" };

/**
 * Account → API keys (ADR 0025): the keys that let the command line, scripts and the SDK act as you. The list shows
 * each by its name and first characters, its scope, and when it was made and last used; the key itself is shown once,
 * when it's made, and never again. A key makes everything but another key, a machine or your notification choices.
 */
export function ApiKeys() {
  useTitle("API keys");
  // The list isn't what the screen paints first, so it's asked for once it has painted: a request made before the
  // first paint counts against the speed budgets (ADR 0018).
  const painted = useAfterPaint();
  const keys = useApiKeys(painted);
  const revoke = useRevokeKey();
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const since = staleSince(keys);

  return (
    <Page title="API keys">
      <Link
        href="/account/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-muted underline"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Account
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <p className="max-w-prose text-small text-text-muted">
        Keys let the <span className="font-semibold">idleo</span> command line, scripts and the SDK act as
        you. Treat a key like a password: it works until you revoke it.
      </p>
      <CreateKey />
      <section aria-labelledby="keys-title" className="flex flex-col gap-1">
        <SectionTitle id="keys-title">Your keys</SectionTitle>
        {revoke.isError && <Banner tone="danger">{problemMessage(revoke.error)}</Banner>}
        {keys.data ? (
          keys.data.keys.length === 0 ? (
            <EmptyState title="No keys yet">
              Make one above, or run <span className="font-semibold">idleo login</span> in a terminal, which
              makes one for that computer.
            </EmptyState>
          ) : (
            <ul className="flex flex-col divide-y divide-divider">
              {keys.data.keys.map((key) => (
                <KeyRow key={key.id} apiKey={key} onRevoke={() => setRevoking(key)} />
              ))}
            </ul>
          )
        ) : keys.isError ? (
          <ErrorState
            error={keys.error}
            what="your API keys"
            onRetry={() => void keys.refetch()}
            retrying={keys.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-divider"
            role="status"
            aria-label="Loading your API keys"
          >
            {[0, 1].map((row) => (
              <div key={row} className={ROW}>
                <div className="flex flex-1 flex-col gap-0.5">
                  <SkeletonLine size="option" width="w-1/2" />
                  <SkeletonLine size="small" width="w-2/3" />
                </div>
                <div className="h-touch w-20 rounded-control skeleton" aria-hidden="true" />
              </div>
            ))}
          </div>
        )}
      </section>
      {revoking !== null && (
        <Suspense fallback={null}>
          <RevokeDialog
            name={revoking.name}
            open
            revoking={revoke.isPending}
            error={revoke.isError ? <Banner tone="danger">{problemMessage(revoke.error)}</Banner> : null}
            onOpenChange={(open) => {
              if (!open) {
                setRevoking(null);
                revoke.reset();
              }
            }}
            onRevoke={() => {
              revoke.mutate(revoking.id, { onSuccess: () => setRevoking(null) });
            }}
          />
        </Suspense>
      )}
    </Page>
  );
}

function KeyRow({ apiKey, onRevoke }: { apiKey: ApiKey; onRevoke: () => void }) {
  return (
    <li className={ROW}>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="truncate text-option font-semibold" title={apiKey.name}>
          {apiKey.name}
        </p>
        <p className="text-small text-text-muted">
          <span className="tabular">{apiKey.prefix}…</span> · {SCOPES[apiKey.scope]} · made{" "}
          <RelativeTime value={apiKey.createdAt} /> ·{" "}
          {apiKey.lastUsedAt ? <RelativeTime value={apiKey.lastUsedAt} prefix="used" /> : "never used"}
        </p>
      </div>
      <Button variant="secondary" aria-label={`Revoke ${apiKey.name}`} onClick={onRevoke}>
        Revoke
      </Button>
    </li>
  );
}
