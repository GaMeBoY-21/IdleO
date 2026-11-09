/**
 * Every path the host site has a screen for (src/sites/host/routes.tsx, src/sites/frame.tsx), and the module that draws
 * it, for scripts/postbuild.ts, as the renter app's src/sites/app/screens.ts.
 */
import { SHARED_SCREEN_MODULES } from "../screen-modules.ts";
import { HOST_PATHS } from "./paths.ts";

export const SCREENS: Record<string, string | null> = {
  ...SHARED_SCREEN_MODULES,
  [HOST_PATHS.home]: "src/features/hosting/Hosting.tsx",
  [HOST_PATHS.laptop]: "src/features/hosting/Machine.tsx",
  [HOST_PATHS.price]: "src/features/hosting/Price.tsx",
  [HOST_PATHS.sessions]: "src/features/hosting/HostSessions.tsx",
  [HOST_PATHS.earnings]: "src/features/hosting/Earnings.tsx",
  [HOST_PATHS.payoutDetails]: "src/features/hosting/PayoutDetails.tsx",
  [HOST_PATHS.add]: "src/features/hosting/AddMachine.tsx",
  // A link has its id in the address, /link/<id>/; the host answers every such address with this page (scripts/serve.ts).
  [HOST_PATHS.link]: "src/features/hosting/LinkApproval.tsx",
};
