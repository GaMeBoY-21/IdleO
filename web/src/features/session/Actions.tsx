import { useMutation, useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useEffect, useState, type ReactNode } from "react";
import { api, data } from "@/api/client";
import { keys, writeSession, type Session } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Tile } from "@/components/ui/Tile";
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
export function StopAction({
  session,
  laptop,
  inside = false,
}: {
  session: Session;
  laptop: boolean;
  /** On a laptop the button sits inside the night band, beside the time and the cost. */
  inside?: boolean;
}) {
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
  const button = (
    <>
      <Button variant={inside ? "secondary" : "ghost"} large block={!inside} onClick={() => setAsking(true)}>
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
    </>
  );
  if (inside) {
    return (
      <div className="flex max-w-menu flex-col gap-2">
        {button}
        <p className="text-caption text-on-night-3">
          It saves a checkpoint first. Nothing more is charged once it stops.
        </p>
      </div>
    );
  }
  return (
    <ActionFoot
      laptop={laptop}
      note={
        laptop
          ? "It saves a checkpoint first. Nothing more is charged once it stops."
          : "Saves a checkpoint first. Nothing more is charged."
      }
    >
      {button}
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
      <Button variant="ghost" large block busy={cancel.isPending} onClick={() => cancel.mutate()}>
        Cancel request
      </Button>
    </ActionFoot>
  );
}

/**
 * The foot of a session's screen. On a phone it's the action bar (web/DESIGN.md "The phone action bar"): stuck to
 * the bottom of the screen on the page's colour, in space of its own in the page, so it covers nothing; on a laptop,
 * a white tile in the column.
 */
export function ActionFoot({
  laptop,
  note,
  children,
}: {
  laptop: boolean;
  note: ReactNode;
  children: ReactNode;
}) {
  const inside = (
    <>
      {children}
      {note && (
        <p className={cn("text-center text-text-2", laptop ? "text-small" : "text-caption")}>{note}</p>
      )}
    </>
  );
  return laptop ? (
    <Tile className="flex flex-col gap-3">{inside}</Tile>
  ) : (
    <div
      data-action-bar=""
      className="sticky bottom-0 z-10 -mx-3.5 mt-auto flex flex-col gap-2 bg-canvas px-3.5 pt-3 pb-3.5 md:-mx-6 md:px-6"
    >
      {inside}
    </div>
  );
}
