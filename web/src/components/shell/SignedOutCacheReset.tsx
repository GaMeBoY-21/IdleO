import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { forget } from "@/api/persisted-cache";
import { useAuth } from "@/auth";

/** Signing out, or a different person signing in, must never show the last person's data. */
export function SignedOutCacheReset() {
  const auth = useAuth();
  const client = useQueryClient();
  useEffect(() => {
    if (auth.status === "signed-out") {
      client.clear();
      void forget();
    }
  }, [auth.status, client]);
  return null;
}
