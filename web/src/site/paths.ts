/**
 * The public pages' addresses (ADR 0029), in one place. The landing page isn't at "/", because "/" is the signed-in
 * Home (src/screens.ts); where the landing page sits once the site and the app have their own addresses is a decision
 * for the deployment round, and it is this constant that changes.
 */
export const LANDING = "/about/";
export const PRICING = "/pricing/";
export const GPUS = "/gpus/";
export const EARN = "/earn/";
export const DOCS = "/docs/";
export const TERMS = "/terms/";
export const PRIVACY = "/privacy/";
export const CONTACT = "/contact/";

/** The app's own addresses, for the buttons that leave the public site. */
export const SIGN_IN = "/sign-in/";
export const START_HOSTING = "/hosting/";
