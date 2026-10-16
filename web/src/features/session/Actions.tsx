import { useMutation, useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useEffect, useState, type ReactNode } from "react";
import { api, data } from "@/api/client";
import { keys, writeSession, type Session } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { afterFirstScreen } from "@/lib/after-first-screen";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";

// The dialog (Radix) isn't needed to show the session: it loads after the first screen, or when Stop is pressed.
const loadStopDialog = () => import("./StopDialog");
const StopDialog = lazy(() => loadStopDialog().then((module) => ({ default: module.StopDialog })));

/** A session the server has just returned: into the cache, and the credit it holds read again. */
function useSettle() {
  const client = useQueryClient();
  return (next: Session) => {
    writeSession(client, next);
    void client.invalidateQueries({ queryKey: keys.wallet });
  };
}

const failed = (error: unknown): ReactNode => <Banner tone="danger">{problemMessage(error)}</Banner>;

/** "Stop session", outlined, asking first in the one dialog the app has (web/DESIGN.md principle 5). */
export function StopAction({ session, laptop }: { session: Session; laptop: boolean }) {
  const settle = useSettle();
  const [asking, setAsking] = useState(false);
  const stop = useMutation({
    mutationFn: () =>
      data(
        api.POST("/v1/sessions/{id}/stop", {
          params: { path: { id: session.id } },
          body: { checkpointFirst: true },
        }),
      ),
    onSuccess: (next) => {
      settle(next);
      setAsking(false);
    },
  });
  useEffect(() => afterFirstScreen(() => void loadStopDialog()), []);
  return (
    <ActionFoot
      laptop={laptop}
      note={
        laptop
          ? "It saves a checkpoint first. Nothing more is charged once it stops."
          : "Saves a checkpoint first. Nothing more is charged."
      }
    >
      <Button variant="secondary" large block onClick={() => setAsking(true)}>
        Stop session
      </Button>
      {asking && (
        <Suspense fallback={null}>
          <StopDialog
            open={asking}
            onOpenChange={(open) => {
              setAsking(open);
              stop.reset();
            }}
            onStop={() => stop.mutate()}
            stopping={stop.isPending}
            error={stop.isError ? failed(stop.error) : null}
          />
        </Suspense>
      )}
    </ActionFoot>
  );
}

/** "Cancel request", while we find its GPU or it starts: nothing has been charged yet. */
export function CancelAction({ session, laptop }: { session: Session; laptop: boolean }) {
  const settle = useSettle();
  const cancel = useMutation({
    mutationFn: () => data(api.POST("/v1/sessions/{id}/cancel", { params: { path: { id: session.id } } })),
    onSuccess: settle,
  });
  return (
    <ActionFoot laptop={laptop} note={null}>
      {cancel.isError && failed(cancel.error)}
      <Button variant="secondary" large block busy={cancel.isPending} onClick={() => cancel.mutate()}>
        Cancel request
      </Button>
    </ActionFoot>
  );
}

/**
 * The foot of a session's screen. On a phone it's the action bar (web/DESIGN.md "The phone action bar"): stuck to
 * the bottom of the screen, with a divider above it, in space of its own in the page, so it covers nothing; on a
 * laptop, the end of the left panel.
 */
export function ActionFoot({
  laptop,
  note,
  children,
  atEnd = true,
}: {
  laptop: boolean;
  note: ReactNode;
  children: ReactNode;
  /** On a laptop, at the foot of the panel; false keeps it in the flow, under what's above it. */
  atEnd?: boolean;
}) {
  return (
    <div
      data-action-bar={laptop ? undefined : ""}
      className={cn(
        "flex flex-col",
        laptop
          ? cn("gap-3", atEnd && "mt-auto pt-2")
          : "sticky bottom-0 z-10 -mx-4 mt-auto gap-2 border-t border-divider bg-ground px-4 pt-3 pb-5 md:-mx-6 md:px-6",
      )}
    >
      {children}
      {note && (
        <p className={cn("text-center text-text-muted", laptop ? "text-small" : "text-caption")}>{note}</p>
      )}
    </div>
  );
}
