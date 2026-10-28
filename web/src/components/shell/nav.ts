import { Activity, House, Server, Wallet, Zap, type LucideIcon } from "lucide-react";

/**
 * Where a renter can go (web/DESIGN.md "Navigation"): the sidebar from 768 px lists all six, and the phone's tab bar
 * the five most used (Home, Book, Sessions, Hosting, Credit) with "More" for the rest (My files, Account, the theme and
 * signing out). `label` is the sidebar's; `tab` and `icon` are the tab bar's.
 */
export const destinations = [
  { href: "/", label: "Home", tab: "Home", icon: House },
  { href: "/book/", label: "Book a GPU", tab: "Book", icon: Zap },
  { href: "/sessions/", label: "Sessions", tab: "Sessions", icon: Activity },
  { href: "/files/", label: "My files", tab: null, icon: null },
  { href: "/hosting/", label: "Hosting", tab: "Hosting", icon: Server },
  { href: "/credit/", label: "Credit", tab: "Credit", icon: Wallet },
] as const satisfies ReadonlyArray<{
  href: string;
  label: string;
  tab: string | null;
  icon: LucideIcon | null;
}>;

/** The five destinations the phone's tab bar shows. */
export const phoneDestinations = destinations.flatMap(({ href, tab, icon }) =>
  tab !== null && icon !== null ? [{ href, label: tab, icon }] : [],
);

export const isCurrent = (pathname: string, href: string) => {
  if (href === "/") {
    return pathname === "/";
  }
  if (href === "/sessions/") {
    return pathname.startsWith("/session");
  }
  return pathname.startsWith(href);
};

/** Screens that are flows on a phone: full screen, with their own back button instead of the bars. */
export const isFlow = (pathname: string) =>
  pathname.startsWith("/book") ||
  pathname === "/session/" ||
  // A machine and what you do with it are flows; the Hosting tab and its earnings keep the bars.
  (pathname.startsWith("/hosting/") && pathname !== "/hosting/" && pathname !== "/hosting/earnings/");
