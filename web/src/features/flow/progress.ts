import type { Session } from "@/api/queries";
import { gpuName, withArticle } from "@/lib/gpu";
import type { Stop } from "./Route";

const FINDING = new Set(["REQUESTED", "MATCHED"]);
const RUNNING = new Set(["RUNNING", "HOST_LOST", "FINISHING"]);

/**
 * A live session's progress as a route (web/DESIGN.md): its data, a GPU picking it up, running with its checkpoints,
 * its outputs. The stop it's at is current; those behind it are done.
 */
export function progressStops(session: Session): Stop[] {
  const finding = FINDING.has(session.state);
  const running = RUNNING.has(session.state);
  const saved = session.checkpoints.length;
  return [
    ...(session.input
      ? [
          {
            title: "Your data",
            detail: session.input.url ? "From your link" : "Uploaded",
            state: "done",
          } as const,
        ]
      : []),
    {
      title: finding ? "Finding your GPU" : "A GPU picked it up",
      detail: session.gpu
        ? `${withArticle(gpuName(session.gpu.model))} took it`
        : "We match the best free GPU for this job, usually within a minute",
      state: finding ? "current" : "done",
    },
    {
      title: "It runs, saving as it goes",
      detail:
        saved === 0
          ? "It saves checkpoints as it runs"
          : `${saved} checkpoint${saved === 1 ? "" : "s"} saved`,
      state: running ? "current" : "next",
    },
    { title: "Your outputs", detail: "Ready to download when it's done", state: "end" },
  ];
}
