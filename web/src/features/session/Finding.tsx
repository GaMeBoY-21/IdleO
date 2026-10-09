import { Check } from "lucide-react";
import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { cn } from "@/lib/cn";
import { gigabytes, gpuName, withArticle } from "@/lib/gpu";
import { CancelAction } from "./Actions";
import { Banners } from "./Banners";
import { sessionInput } from "@/lib/inputs";

/** What the screen knows of the session's data: its upload's name, once read. */
export interface DataName {
  filename: string | null;
}

/**
 * While we find its GPU (`REQUESTED`, `MATCHED`) and once one is found (`PROVISIONING`), like a ride app finding a
 * driver (docs/design/redesign/matching-phone.html): the wordmark's ring, large and slowly breathing (still under
 * reduced motion), what's done, that nothing is charged until it runs, and "Cancel request".
 */
export function Finding({ session, data, laptop }: { session: Session; data: DataName; laptop: boolean }) {
  const found = session.state === "PROVISIONING" ? session.gpu : null;
  const gpu = found ? `${gpuName(found.model)} · ${gigabytes(found.vramMb)}` : null;
  return (
    <>
      <div
        className={cn(
          "flex flex-1 flex-col gap-7",
          laptop ? "items-start" : "items-center justify-center px-2 py-6 text-center",
        )}
      >
        <FindingRing />
        <div className="flex flex-col gap-2" aria-live="polite">
          <h1 className={laptop ? "text-question" : "text-title"}>
            {found ? "GPU found" : "Finding your GPU"}
          </h1>
          <p className="text-body text-text-muted">
            {gpu ?? "Asking the best free GPU for this job. Usually under a minute."}
          </p>
          {/* Always there, so nothing moves when a GPU is found. */}
          <p className="min-h-6 text-body font-semibold">{found && "Starting…"}</p>
        </div>
        <Banners session={session} />
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
      </div>
      <CancelAction session={session} laptop={laptop} />
    </>
  );
}

function Row({ done, children }: { done: boolean; children: ReactNode }) {
  return (
    <li className="flex items-center gap-3 rounded-control bg-fill px-4 py-3.5">
      {done ? (
        <Check aria-hidden="true" className="size-5 shrink-0 text-running" strokeWidth={2} />
      ) : (
        <span aria-hidden="true" className="flex size-5 shrink-0 items-center justify-center">
          <span className="size-3 rounded-full bg-text" />
        </span>
      )}
      <span className="min-w-0 flex-1 text-body break-words">{children}</span>
    </li>
  );
}

/** The wordmark's ring (src/brand/Wordmark.tsx), large, on a grey disc with two soft halos. */
export function FindingRing() {
  return (
    <div aria-hidden="true" className="rounded-full bg-fill/30 p-6">
      <div className="rounded-full bg-fill/60 p-5">
        <div className="flex size-finding items-center justify-center rounded-full bg-fill">
          <svg viewBox="0 0 22 22" className="size-finding-ring motion-safe:animate-breathe">
            <circle
              cx="11"
              cy="11"
              r="7.5"
              fill="none"
              className="stroke-brand"
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
