import { Check } from "lucide-react";
import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { Tile, TileLabel } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { gigabytes, gpuName, withArticle } from "@/lib/gpu";
import { sessionInput } from "@/lib/inputs";
import { CancelAction } from "./Actions";
import { Banners } from "./Banners";

/** What the screen knows of the session's data: its upload's name, once read. */
export interface DataName {
  filename: string | null;
}

/**
 * While we find its GPU (`REQUESTED`, `MATCHED`) and once one is found (`PROVISIONING`), like a ride app finding a
 * driver (web/DESIGN.md "The session screen"): one big dark tile with the ring, large and slowly breathing (still under
 * reduced motion), and a small white tile for what was asked for and what's done, that nothing is charged until it
 * runs, and "Cancel request". On a laptop the two sit side by side.
 */
export function Finding({ session, data, laptop }: { session: Session; data: DataName; laptop: boolean }) {
  const found = session.state === "PROVISIONING" ? session.gpu : null;
  const gpu = found ? `${gpuName(found.model)} · ${gigabytes(found.vramMb)}` : null;
  const state = (
    <Tile
      tone="dark"
      className={cn(
        "flex flex-col items-center justify-center gap-7 px-5 py-10 text-center",
        laptop && "min-h-panel",
      )}
    >
      <FindingRing />
      <div className="flex flex-col gap-2" aria-live="polite">
        <h1 className={laptop ? "text-question" : "text-title"}>
          {found ? "GPU found" : "Finding your GPU"}
        </h1>
        <p className="text-body text-dark-muted">
          {gpu ?? "Asking the best free GPU for this job. Usually under a minute."}
        </p>
        {/* Always there, so nothing moves when a GPU is found. */}
        <p className="min-h-6 text-body font-semibold">{found && "Starting…"}</p>
      </div>
    </Tile>
  );
  const asked = (
    <Tile className="flex flex-col gap-3">
      <TileLabel>What you asked for</TileLabel>
      <ul className="flex w-full flex-col gap-2 text-left">
        {sessionInput(session) && (
          <Row done>
            {sessionInput(session)?.url
              ? "Your data's link is ready"
              : data.filename
                ? `${data.filename} uploaded`
                : "Your data is uploaded"}
          </Row>
        )}
        {found && <Row done>{`Found ${withArticle(gpuName(found.model))}`}</Row>}
        <Row done={false}>Nothing is charged until it runs</Row>
      </ul>
    </Tile>
  );
  const cancel = <CancelAction session={session} laptop={laptop} />;
  if (!laptop) {
    return (
      <>
        {state}
        <Banners session={session} />
        {asked}
        {cancel}
      </>
    );
  }
  return (
    <div className="grid grid-cols-12 items-stretch gap-3.5">
      <div className="col-span-7 flex flex-col">{state}</div>
      <div className="col-span-5 flex flex-col gap-3.5">
        <Banners session={session} />
        {asked}
        {cancel}
      </div>
    </div>
  );
}

function Row({ done, children }: { done: boolean; children: ReactNode }) {
  return (
    <li className="flex items-center gap-3 rounded-control bg-ground px-4 py-3.5">
      {done ? (
        <Check aria-hidden="true" className="size-5 shrink-0 text-running" strokeWidth={2.5} />
      ) : (
        <span aria-hidden="true" className="flex size-5 shrink-0 items-center justify-center">
          <span className="size-3 rounded-full bg-text" />
        </span>
      )}
      <span className="min-w-0 flex-1 text-body break-words">{children}</span>
    </li>
  );
}

/** The ring (src/brand/Wordmark.tsx's), large, in lime on the dark tile, on a disc with two soft halos. */
export function FindingRing() {
  return (
    <div aria-hidden="true" className="rounded-full bg-dark-fg/5 p-6">
      <div className="rounded-full bg-dark-fg/10 p-5">
        <div className="flex size-finding items-center justify-center rounded-full bg-dark-fg/10">
          <svg viewBox="0 0 22 22" className="size-finding-ring motion-safe:animate-breathe">
            <circle
              cx="11"
              cy="11"
              r="7.5"
              fill="none"
              className="stroke-live"
              strokeWidth="2.6"
              strokeDasharray="40 7"
              transform="rotate(-68 11 11)"
            />
          </svg>
        </div>
      </div>
    </div>
  );
}
