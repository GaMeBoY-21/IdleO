import { Activity, House, Landmark, Plus, Wallet } from "lucide-react";
import { appLink } from "@/lib/site-links";
import { currentByPrefix, type SiteNav } from "../types";
import { HOST_PATHS } from "./paths";

/**
 * The host site's bars (web/DESIGN.md "Navigation"). No renting here (ADR 0058): the renter app is one quiet link in the
 * account menu. A laptop's screens and adding one are flows on a phone, as the session screen is in the renter app.
 */
export const nav: SiteNav = {
  destinations: [
    { href: HOST_PATHS.home, label: "Home" },
    { href: HOST_PATHS.earnings, label: "Earnings" },
    { href: HOST_PATHS.sessions, label: "Sessions run" },
    { href: HOST_PATHS.payoutDetails, label: "Payout details" },
    { href: HOST_PATHS.add, label: "Add a laptop" },
  ],
  phoneDestinations: [
    { href: HOST_PATHS.home, label: "Home", icon: House },
    { href: HOST_PATHS.earnings, label: "Earnings", icon: Wallet },
    { href: HOST_PATHS.sessions, label: "Sessions", icon: Activity },
    { href: HOST_PATHS.payoutDetails, label: "Payouts", icon: Landmark },
    { href: HOST_PATHS.add, label: "Add", icon: Plus },
  ],
  isCurrent: (pathname, href) =>
    href === HOST_PATHS.home
      ? pathname === "/" || pathname.startsWith(HOST_PATHS.laptop)
      : currentByPrefix(pathname, href),
  isFlow: (pathname) =>
    pathname.startsWith(HOST_PATHS.laptop) ||
    pathname.startsWith(HOST_PATHS.link) ||
    pathname === HOST_PATHS.add,
  accountSections: [
    { href: "/account/", label: "Profile and notifications" },
    {
      href: "/account/data/",
      label: "Your data",
      detail: "What we hold, download it, or delete your account",
    },
    { href: "/account/help/", label: "Help", detail: "Write to us, and read our answers" },
  ],
  menuItems: [],
  otherSite: { href: appLink("/"), label: "Rent a GPU" },
};
