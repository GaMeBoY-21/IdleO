import { CreditCard, Cpu, Home, Server } from "lucide-react";

/**
 * Where a renter can go (web/DESIGN.md "Navigation"). On a laptop, pills in the top bar, Home being the wordmark;
 * on a phone, the bottom tabs.
 */
export const laptopDestinations = [
  { href: "/book/", label: "Book" },
  { href: "/sessions/", label: "Sessions" },
  { href: "/hosting/", label: "Hosting" },
  { href: "/credit/", label: "Credit" },
] as const;

export const phoneTabs = [
  { href: "/", label: "Home", icon: Home },
  { href: "/book/", label: "Book", icon: Cpu },
  { href: "/hosting/", label: "Hosting", icon: Server },
  { href: "/credit/", label: "Credit", icon: CreditCard },
] as const;

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
