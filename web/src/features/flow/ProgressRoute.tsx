import type { Session } from "@/api/queries";
import { useNow } from "@/lib/clock";
import type { InputFact } from "@/lib/inputs";
import { progressStops } from "./progress";
import { Route } from "./Route";

/**
 * A session's progress as its route (src/features/flow/progress.ts), moving on with the app's clock so "in about
 * 26 min" stays true. Top to bottom on Home's panel, left to right (`across`) on the session screen's.
 */
export function ProgressRoute({
  session,
  typicalRuntimeSeconds,
  inputs,
  across = false,
}: {
  session: Session;
  typicalRuntimeSeconds: number | null | undefined;
  /** Every input, with its upload's size once read. */
  inputs?: InputFact[];
  across?: boolean;
}) {
  const now = useNow("half-minute");
  return <Route across={across} stops={progressStops(session, { now, typicalRuntimeSeconds, inputs })} />;
}
