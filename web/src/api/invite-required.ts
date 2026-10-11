import { navigate } from "wouter/use-browser-location";
import { ApiError } from "./errors";

/**
 * A waiting account (no invite code yet) is refused everything but its own account with 403 `invite-required`
 * (ADR 0024). Whichever request meets that, the person is taken to the invite step and brought back here after.
 * Checked on the one place every query and action passes, so no screen asks first and nothing is fetched early.
 * Returns whether it was that problem.
 */
export function sendToInviteStep(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.type !== "/problems/invite-required") {
    return false;
  }
  const { pathname, search } = window.location;
  if (!pathname.startsWith("/invite/")) {
    const here = `${pathname}${search}`;
    navigate(here === "/" ? "/invite/" : `/invite/?next=${encodeURIComponent(here)}`, { replace: true });
  }
  return true;
}
