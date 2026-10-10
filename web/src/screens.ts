/**
 * Every path the app has a screen for (src/App.tsx), and the module that draws it. scripts/postbuild.ts writes an
 * index.html into each path, so any static host serves a deep link without a rewrite rule, and preloads that
 * screen's own code in it, so a lazily loaded screen doesn't wait for the entry script to ask for it. The signed-in
 * screens also preload the sign-in adapter, which they need for a token before their first API call.
 */
/** The screens that need a signed-in user's token at once. */
export const SIGNED_IN_SCREENS = [
  "/",
  "/credit/",
  "/session/",
  "/sessions/",
  "/book/",
  "/files/",
  "/hosting/",
  "/hosting/machine/",
  "/hosting/price/",
  "/hosting/sessions/",
  "/hosting/earnings/",
  "/hosting/add/",
  "/welcome/",
];

export const SCREENS: Record<string, string | null> = {
  "/": null,
  "/credit/": "src/features/credit/CreditHistory.tsx",
  "/session/": "src/features/session/SessionDetail.tsx",
  "/sessions/": "src/features/sessions/AllSessions.tsx",
  "/book/": "src/features/book/Book.tsx",
  "/files/": "src/features/files/MyFiles.tsx",
  "/hosting/": "src/features/hosting/Hosting.tsx",
  "/hosting/machine/": "src/features/hosting/Machine.tsx",
  "/hosting/price/": "src/features/hosting/Price.tsx",
  "/hosting/sessions/": "src/features/hosting/HostSessions.tsx",
  "/hosting/earnings/": "src/features/hosting/Earnings.tsx",
  "/hosting/add/": "src/features/hosting/AddMachine.tsx",
  // The form loads just after the screen paints, not before (src/features/sign-in/LazyEmailForm.tsx).
  "/sign-in/": null,
  "/sign-in/finish/": "src/features/sign-in/FinishSignIn.tsx",
  "/welcome/": "src/features/home/Welcome.tsx",
};
