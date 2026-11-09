/**
 * The modules of the screens every site has (src/sites/frame.tsx, SHARED_SCREENS), by path, for each site's screen list
 * (src/sites/<site>/screens.ts), which scripts/postbuild.ts writes a page for.
 */
export const SHARED_SCREEN_MODULES: Record<string, string> = {
  // Its form loads just after the screen paints, not before (src/features/sign-in/LazyEmailForm.tsx).
  "/sign-in/": "src/features/sign-in/SignIn.tsx",
  "/sign-in/finish/": "src/features/sign-in/FinishSignIn.tsx",
  "/agree/": "src/features/agree/Agree.tsx",
  "/invite/": "src/features/invite/Invite.tsx",
  "/account/": "src/features/account/Account.tsx",
  "/account/appeal/": "src/features/account/Appeal.tsx",
  "/account/data/": "src/features/account/YourData.tsx",
  "/account/delete/": "src/features/account/DeleteAccount.tsx",
  "/account/help/": "src/features/help/Help.tsx",
  "/account/help/ticket/": "src/features/help/HelpTicket.tsx",
  "/account/delete/confirm/": "src/features/deletion/ConfirmDeletion.tsx",
  "/account/delete/cancel/": "src/features/deletion/CancelDeletion.tsx",
  "/notifications/": "src/features/notifications/Notifications.tsx",
};
