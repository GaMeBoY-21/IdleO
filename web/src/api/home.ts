import { useQuery } from "@tanstack/react-query";
import { api, data } from "./client";
import type { components } from "./schema.gen";

/**
 * What the renter app's Home asks for besides the account, the sessions and the credit (ADR 0058): this month's use, and
 * the typical price of each GPU size, which a service's typical cost is worked out from. Neither is in a first paint.
 */
export type MonthUsage = components["schemas"]["MonthUsage"];
export type TypicalPrices = components["schemas"]["PublicAvailabilityAnswer"];

export function useUsage(enabled: boolean) {
  return useQuery({ queryKey: ["usage"], enabled, queryFn: () => data(api.GET("/v1/usage")) });
}

/** The typical price an hour of each GPU size, from the same figures the website shows. */
export function useTypicalPrices(enabled: boolean) {
  return useQuery({
    queryKey: ["typical-prices"],
    enabled,
    staleTime: 10 * 60_000,
    queryFn: () => data(api.GET("/v1/public/availability")),
  });
}
