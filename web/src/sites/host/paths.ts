/**
 * The host site's addresses (ADR 0058), in one place, and where each used to be in the renter app, before hosting had a
 * site of its own. The renter app answers every old address with a permanent redirect here (scripts/postbuild.ts),
 * query and all, so emails, bookmarks and older Windows apps keep working.
 *
 * Read by the build too, so it imports nothing.
 */
export const HOST_PATHS = {
  home: "/",
  laptops: "/laptops/",
  laptop: "/laptop/",
  price: "/laptop/price/",
  sessions: "/sessions/",
  earnings: "/earnings/",
  payouts: "/payouts/",
  payoutDetails: "/payout-details/",
  help: "/help/",
  add: "/add/",
  /** A host link's approval: `/link/<id>/`. */
  link: "/link/",
} as const;

/** A laptop's own screens, by its id. */
export const laptopPath = (id: string) => `${HOST_PATHS.laptop}?id=${id}`;
export const pricePath = (id: string) => `${HOST_PATHS.price}?id=${id}`;
export const laptopSessionsPath = (id: string) => `${HOST_PATHS.sessions}?id=${id}`;

/**
 * The renter app's old hosting addresses and the host site's paths they moved to. A key ending in `*` is a prefix:
 * what follows it is kept (`/link/<id>/`).
 */
export const MOVED_FROM_APP: Readonly<Record<string, string>> = {
  "/hosting/": HOST_PATHS.home,
  "/hosting/machine/": HOST_PATHS.laptop,
  "/hosting/price/": HOST_PATHS.price,
  "/hosting/sessions/": HOST_PATHS.sessions,
  "/hosting/earnings/": HOST_PATHS.earnings,
  "/hosting/payout-details/": HOST_PATHS.payoutDetails,
  "/hosting/add/": HOST_PATHS.add,
  "/link/*": `${HOST_PATHS.link}*`,
};
