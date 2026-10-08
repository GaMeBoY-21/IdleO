import type { ReactNode } from "react";
import { Route, type Stop } from "./Route";

/** The session's data, as the route's first stop: what it is and whether it's ready. */
export interface DataStop {
  detail: ReactNode;
  done: boolean;
}

/**
 * What happens after "Request a GPU", as a route (docs/design/redesign/book-laptop.html): your data, a GPU picks it
 * up, it runs and saves as it goes, your outputs. Book's right panel, and Home's when nothing is running. `data` is
 * null for a template that takes no input; `runtime` is the template's typical runtime ("about 45 min"), if known.
 */
export function YourSession({
  data,
  runtime,
  chips,
}: {
  data: DataStop | null;
  runtime: string | null;
  chips?: ReactNode;
}) {
  const from = data === null ? "From a free GPU" : "From your data";
  const stops: Stop[] = [
    ...(data === null
      ? []
      : [{ title: "Your data", detail: data.detail, state: data.done ? "done" : "next" } as const]),
    {
      title: "A host picks it up",
      detail: "We match the best free GPU for this job, usually within a minute",
      state: "next",
    },
    {
      title: "It runs, saving as it goes",
      detail:
        "It saves checkpoints as it runs. If a host drops, another carries on and you don't pay for lost time.",
      state: "next",
    },
    { title: "Your outputs", detail: "Ready to download when it's done", state: "end" },
  ];
  return (
    <>
      <div className="flex flex-col gap-1.5">
        <p className="text-small font-semibold text-text-muted">Your session</p>
        <h2 className="text-title">
          {from} to your outputs{runtime === null ? "" : `, in ${runtime}`}
        </h2>
      </div>
      <Route stops={stops} />
      {chips && <div className="flex flex-wrap gap-2">{chips}</div>}
    </>
  );
}
