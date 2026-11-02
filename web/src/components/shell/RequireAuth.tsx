import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { useLocation, useSearch } from "wouter";
import { keptUser, refreshRestored, restoreFor } from "@/api/persisted-cache";
import { useAuth } from "@/auth";
import { afterPaint } from "@/lib/after-first-screen";

/**
 * The signed-in screens: a signed-out visitor goes to sign-in, and comes back here afterwards. The last figures of
 * whoever was here last are restored (src/api/persisted-cache.ts) before the screen shows, and refreshed just after its
 * first frame is painted, so that frame doesn't wait for the sign-in adapter, which loads after it (src/main.tsx). With
 * none kept, the screen waits for sign-in to say who's here.
 */
export function RequireAuth({ children, fallback }: { children: ReactNode; fallback: ReactNode }) {
  const auth = useAuth();
  const client = useQueryClient();
  const [path, navigate] = useLocation();
  const search = useSearch();
  const [restoredFor, setRestoredFor] = useState<string | null>(null);
  const [kept, setKept] = useState<string | null>(null);
  const uid = auth.status === "signed-in" ? auth.user.uid : auth.status === "loading" ? kept : null;

  useEffect(() => {
    void keptUser().then(setKept);
  }, []);

  useEffect(() => {
    if (auth.status === "signed-out") {
      const here = `${path}${search ? `?${search}` : ""}`;
      const params = new URLSearchParams();
      if (here !== "/") {
        params.set("next", here);
      }
      if (auth.reason === "expired") {
        params.set("expired", "1");
      }
      const query = params.toString();
      navigate(`/sign-in/${query ? `?${query}` : ""}`, { replace: true });
    }
  }, [auth, path, search, navigate]);

  useEffect(() => {
    if (uid !== null) {
      void restoreFor(client, uid).finally(() => setRestoredFor(uid));
    }
  }, [client, uid]);

  const restored = uid !== null && restoredFor === uid;
  useEffect(() => {
    // Once the screen drawn from the restored figures has painted, before anything is refetched.
    return restored ? afterPaint(() => refreshRestored(client)) : undefined;
  }, [client, restored]);

  return restored ? children : fallback;
}
