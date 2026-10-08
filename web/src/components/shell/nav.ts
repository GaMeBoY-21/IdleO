import { CreditCard, Cpu, Home } from "lucide-react";

/**
 * Where a renter can go (web/DESIGN.md "Navigation"). On a laptop, pills in the top bar, Home being the wordmark;
 * on a phone, the bottom tabs. Hosting joins both when it exists.
 */
export const laptopDestinations = [
  { href: "/book/", label: "Book" },
  { href: "/sessions/", label: "Sessions" },
  { href: "/credit/", label: "Credit" },
] as const;

export const phoneTabs = [
  { href: "/", label: "Home", icon: Home },
  { href: "/book/", label: "Book", icon: Cpu },
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
export const isFlow = (pathname: string) => pathname.startsWith("/book") || pathname === "/session/";
