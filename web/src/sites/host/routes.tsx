import { defineSite, screen } from "../frame";
import { HOST_PATHS } from "./paths";

/**
 * The host site (host.idleo.in, ADR 0058): a host's laptops, their prices, what they earned and their payouts. No renting
 * screen is here.
 */
const link = screen(() => import("@/features/hosting/LinkApproval").then((m) => m.LinkApprovalScreen));

const screens = {
  [HOST_PATHS.home]: screen(() => import("@/features/hosting/Hosting").then((m) => m.Hosting)),
  [HOST_PATHS.laptop]: screen(() => import("@/features/hosting/Machine").then((m) => m.Machine)),
  [HOST_PATHS.price]: screen(() => import("@/features/hosting/Price").then((m) => m.Price)),
  [HOST_PATHS.sessions]: screen(() => import("@/features/hosting/HostSessions").then((m) => m.HostSessions)),
  [HOST_PATHS.earnings]: screen(() => import("@/features/hosting/Earnings").then((m) => m.Earnings)),
  [HOST_PATHS.payoutDetails]: screen(() =>
    import("@/features/hosting/PayoutDetails").then((m) => m.PayoutDetails),
  ),
  [HOST_PATHS.add]: screen(() => import("@/features/hosting/AddMachine").then((m) => m.AddMachine)),
  // The page is written at /link/ and answers /link/<id>/ (scripts/postbuild.ts's rewrite); the id is read from the path.
  [HOST_PATHS.link]: link,
  [`${HOST_PATHS.link}:id/`]: link,
};

export const { App, preloadScreen } = defineSite({ screens, signedIn: Object.keys(screens) });
