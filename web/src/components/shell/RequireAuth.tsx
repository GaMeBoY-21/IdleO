import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { useLocation, useSearch } from "wouter";
import { restoreFor } from "@/api/persisted-cache";
import { useAuth } from "@/auth";

/**
 * The signed-in screens: a signed-out visitor goes to sign-in, and comes back here afterwards. Once sign-in has
 * said who's here, their last figures are restored (src/api/persisted-cache.ts) before the screen shows.
 */
export function RequireAuth({ children, fallback }: { children: ReactNode; fallback: ReactNode }) {
  const auth = useAuth();
  const client = useQueryClient();
  const [path, navigate] = useLocation();
  const search = useSearch();
  const [restoredFor, setRestoredFor] = useState<string | null>(null);
  const uid = auth.status === "signed-in" ? auth.user.uid : null;

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

  return uid !== null && restoredFor === uid ? children : fallback;
}
