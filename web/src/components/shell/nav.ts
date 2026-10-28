import { Activity, House, Server, Wallet, Zap, type LucideIcon } from "lucide-react";

/**
 * Where a renter can go (web/DESIGN.md "Navigation"): the sidebar from 768 px lists all six, and the phone's tab bar
 * ({@link phoneDestinations}) the five most used with "More" for the rest (My files, Account, signing out).
 */
export const destinations = [
  { href: "/", label: "Home" },
  { href: "/book/", label: "Book a GPU" },
  { href: "/sessions/", label: "Sessions" },
  { href: "/files/", label: "My files" },
  { href: "/hosting/", label: "Hosting" },
  { href: "/credit/", label: "Credit" },
] as const;

/** The five destinations the phone's tab bar shows. */
export const phoneDestinations: ReadonlyArray<{ href: string; label: string; icon: LucideIcon }> = [
  { href: "/", label: "Home", icon: House },
  { href: "/book/", label: "Book", icon: Zap },
  { href: "/sessions/", label: "Sessions", icon: Activity },
  { href: "/hosting/", label: "Hosting", icon: Server },
  { href: "/credit/", label: "Credit", icon: Wallet },
];

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
