import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { config } from "@/config";
import { apiFetch } from "./client";
import { readNewLogs } from "./logs";
import { useSession, writeSession } from "./queries";
import { followSession, type StreamStatus } from "./session-follower";

/**
 * One session, live, while its screen is open (ADR 0019). Its event stream writes each snapshot into the query
 * cache (writeSession), so this screen, Home and All sessions read one copy, and each `logs` event reads the new
 * chunks. While the stream is down, the session is read every 5 seconds instead, and the screen says its figures
 * may be stale. Once the session is settled, nothing is followed any more.
 */
export function useLiveSession(id: string | null) {
  const client = useQueryClient();
  const [status, setStatus] = useState<StreamStatus>("connecting");
  const session = useSession(id, status === "down");
  const settled = session.data?.settled ?? false;

  useEffect(() => {
    if (id === null || settled) {
      return;
    }
    const controller = new AbortController();
    void followSession(
      {
        open: (signal) =>
          apiFetch(
            new Request(`${config.apiUrl}/v1/sessions/${encodeURIComponent(id)}/events`, {
              headers: { Accept: "text/event-stream" },
              signal,
            }),
          ),
        onSession: (snapshot) => writeSession(client, snapshot),
        onLogs: () => void readNewLogs(client, id).catch(() => undefined),
        onStatus: setStatus,
      },
      controller.signal,
    );
    return () => controller.abort();
  }, [client, id, settled]);

  return { session, stream: id === null || settled ? ("ended" as const) : status };
}
