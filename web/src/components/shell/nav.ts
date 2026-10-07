import { CreditCard, Cpu, Home } from "lucide-react";

/** The renter's destinations, the same on a phone (bottom tabs) and a laptop (top links). */
export const destinations = [
  { href: "/", label: "Home", icon: Home },
  { href: "/book/", label: "Book a GPU", short: "Book", icon: Cpu },
  { href: "/credit/", label: "Credit", icon: CreditCard },
] as const;

export const isCurrent = (pathname: string, href: string) =>
  href === "/" ? pathname === "/" || pathname.startsWith("/session") : pathname.startsWith(href);
