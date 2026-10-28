import { Check } from "lucide-react";
import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { Tile, TileLabel } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { gigabytes, gpuName, withArticle } from "@/lib/gpu";
import { downloadLine } from "@/lib/images";
import { inputLine, type InputFact } from "@/lib/inputs";
import { CancelAction } from "./Actions";
import { RailColumns } from "@/components/shell/Layout";
import { Banners } from "./Banners";

/**
 * While we find its GPU (`REQUESTED`, `MATCHED`) and once one is found (`PROVISIONING`), like a ride app finding a
 * driver (web/DESIGN.md "The session screen"): one big dark tile with the ring, large and slowly breathing (still under
 * reduced motion), and a small white tile for what was asked for and what's done, that nothing is charged until it
 * runs, and "Cancel request". On a laptop the two sit side by side.
 */
export function Finding({
  session,
  inputs,
  laptop,
  steps,
  request,
}: {
  session: Session;
  /** Every input, with its upload's size once read. */
  inputs: InputFact[];
  laptop: boolean;
  /** The steps tile and the request tile, the laptop's right column. */
  steps: ReactNode;
  request: ReactNode;
}) {
  const found = session.state === "PROVISIONING" ? session.gpu : null;
  const gpu = found ? `${gpuName(found.model)} · ${gigabytes(found.vramMb)}` : null;
  const state = (
    <Tile
      tone="night"
      className={cn(
        "flex flex-col items-center justify-center gap-7 px-5 py-10 text-center",
        laptop && "min-h-panel xl:flex-1",
      )}
    >
      <FindingRing />
      <div className="flex flex-col gap-2" aria-live="polite">
        <h1 className={laptop ? "font-title text-display-panel" : "font-title text-display-feature"}>
          {found ? "GPU found" : "Finding your GPU"}
        </h1>
        <p className="text-body text-on-night-2">
          {gpu ?? "Asking the best free GPU for this job. Usually under a minute."}
        </p>
        {/* Always there, so nothing moves when a GPU is found. */}
        <p className="min-h-6 text-body font-semibold">
          {found && (session.imageDownload ? downloadLine(session.imageDownload) : "Starting…")}
        </p>
      </div>
    </Tile>
  );
  const asked = (
    <Tile className="flex flex-col gap-3">
      <TileLabel>What you asked for</TileLabel>
      <ul className="flex w-full flex-col gap-2 text-left">
        {inputs.map((input) => (
          <Row key={input.name} done>
            {`${input.label} · ${inputLine(input)}`}
          </Row>
        ))}
        {found && <Row done>{`Found ${withArticle(gpuName(found.model))}`}</Row>}
        <Row done={false}>
          {session.imageId
            ? "Nothing is charged until it runs, not even your image's download"
            : "Nothing is charged until it runs"}
        </Row>
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
    <RailColumns
      left={
        <>
          {state}
          <Banners session={session} />
          {cancel}
        </>
      }
      right={
        <>
          {steps}
          {request}
        </>
      }
    />
  );
}

function Row({ done, children }: { done: boolean; children: ReactNode }) {
  return (
    <li className="flex items-center gap-3 rounded-control bg-panel-2 px-4 py-3.5">
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
    <div aria-hidden="true" className="rounded-full bg-on-night/5 p-6">
      <div className="rounded-full bg-on-night/10 p-5">
        <div className="flex size-finding items-center justify-center rounded-full bg-on-night/10">
          <svg viewBox="0 0 22 22" className="size-finding-ring motion-safe:animate-breathe">
            <circle
              cx="11"
              cy="11"
              r="7.5"
              fill="none"
              className="stroke-volt"
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
