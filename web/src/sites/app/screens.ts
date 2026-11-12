/**
 * Every path the renter app has a screen for (src/sites/app/routes.tsx, src/sites/frame.tsx), and the module that draws
 * it, as each site has one. scripts/postbuild.ts writes an index.html into each path, so any static host serves a deep
 * link without a rewrite rule, and preloads that screen's own code in it, so a lazily loaded screen doesn't wait for the
 * entry script to ask for it. The console's old /admin/ addresses aren't here: they redirect to the admin site.
 */
import { SHARED_SCREEN_MODULES } from "../screen-modules.ts";

export const SCREENS: Record<string, string | null> = {
  ...SHARED_SCREEN_MODULES,
  "/": "src/features/home/Home.tsx",
  "/credit/": "src/features/credit/CreditHistory.tsx",
  "/session/": "src/features/session/SessionDetail.tsx",
  "/sessions/": "src/features/sessions/AllSessions.tsx",
  "/book/": "src/features/book/Book.tsx",
  "/files/": "src/features/files/MyFiles.tsx",
  "/welcome/": "src/features/home/Welcome.tsx",
  "/account/keys/": "src/features/account/ApiKeys.tsx",
  "/account/reports/": "src/features/account/Reports.tsx",
  "/account/images/": "src/features/account/MyImages.tsx",
  "/account/pan/": "src/features/account/YourPan.tsx",
  "/cli-login/": "src/features/cli-login/CliLogin.tsx",
};
