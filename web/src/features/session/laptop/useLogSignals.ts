import { skipToken, useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import type { LogState } from "@/api/logs";
import { keys } from "@/api/queries";
import { readSignals } from "@/lib/log-signals";

/** What the log read so far says about the job's progress: the epoch, the step and the loss. Nothing until it's read. */
export function useLogSignals(id: string) {
  const log = useQuery<LogState>({ queryKey: keys.logs(id), queryFn: skipToken });
  return useMemo(() => readSignals(id, log.data), [id, log.data]);
}
