import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";

/**
 * The PAN on file (ADR 0040, ADR 0042), shared by the host site, where payouts need it, and the renter app, where a
 * renter's own images do. It's part of the person's payout details: one question, one cache entry on either site.
 */
export const PAYOUT_DETAILS_KEY = ["payout-details"] as const;

export const payoutDetailsQuery = {
  queryKey: PAYOUT_DETAILS_KEY,
  queryFn: () => data(api.GET("/v1/payout-details")),
};

/** The payout details, for the PAN in them. */
export function usePanDetails(enabled = true) {
  return useQuery({ ...payoutDetailsQuery, enabled });
}

/**
 * Gives the PAN and the name as on it; the cache holds what the server answered. What the PAN unlocks is asked again:
 * a host's payouts, and a renter's images.
 */
export function useSubmitPan() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { pan: string; name: string }) => data(api.PUT("/v1/payout-details/pan", { body })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: PAYOUT_DETAILS_KEY });
      void client.invalidateQueries({ queryKey: ["payouts", "summary"] });
      void client.invalidateQueries({ queryKey: ["images"] });
    },
  });
}
