/**
 * The public pages' addresses (ADR 0029), in one place. The website has its own address, and the landing page is its
 * `/`; the app has another (`IDLEO_APP_URL`). A page of the website is a path on the website; everything that goes into
 * the app is built here from the app's address, so no page spells one out.
 */
export const LANDING = "/";
export const PRICING = "/pricing/";
export const GPUS = "/gpus/";
export const EARN = "/earn/";
export const DOCS = "/docs/";
/** The API reference's operations, and its schemas on a page of their own (the operations' page stays quick to load). */
export const API_OPERATIONS = "/docs/api/";
export const API_SCHEMAS = "/docs/api/schemas/";
export const TERMS = "/terms/";
export const PRIVACY = "/privacy/";
export const CONTACT = "/contact/";
/** The setup app's "Show me how" pages, one per laptop maker: `/help/virtualization/<maker>/`. */
export const HELP_VIRTUALIZATION = "/help/virtualization/";
/** Where the landing page used to be, before the website had its own address: it answers with a redirect to `/`. */
export const OLD_LANDING = "/about/";

/** The paths the app has for the buttons that leave the website. */
const APP_SIGN_IN = "/sign-in/";
const APP_HOSTING = "/hosting/";

/** The addresses the website's buttons lead to, in the app. */
export interface EntryLinks {
  /** "Log in": the app's sign-in. */
  logIn: string;
  /** "Get started": the same, which says "Create your account" because of `start=1`. */
  getStarted: string;
  /** "Start hosting": the app's Hosting tab, through sign-in if the person isn't. */
  startHosting: string;
}

/** The app's links from its address (`IDLEO_APP_URL`), with or without a trailing slash. */
export function entryLinks(appUrl: string): EntryLinks {
  const base = appUrl.replace(/\/+$/, "");
  return {
    logIn: `${base}${APP_SIGN_IN}`,
    getStarted: `${base}${APP_SIGN_IN}?start=1`,
    startHosting: `${base}${APP_HOSTING}`,
  };
}
