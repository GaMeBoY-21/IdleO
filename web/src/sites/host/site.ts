import { House, Landmark, Laptop, LifeBuoy, Wallet } from "lucide-react";
import { appLink } from "@/lib/site-links";
import { currentByPrefix, type SiteNav } from "../types";
import { HOST_PATHS } from "./paths";

/**
 * The host site's bars (web/DESIGN.md "Navigation"). No renting here (ADR 0058): the renter app is one quiet link in the
 * account menu. A laptop's screens and adding one are flows on a phone, as the session screen is in the renter app.
 */
export const nav: SiteNav = {
  side: "hosting",
  locked: {
    title: "Hosting isn't open for your account yet",
    body: "Your account can rent GPUs, and hosting a laptop is unlocked by its own invite code. Enter one to start earning.",
  },
  destinations: [
    { href: HOST_PATHS.home, label: "Home" },
    { href: HOST_PATHS.laptops, label: "Laptops" },
    { href: HOST_PATHS.earnings, label: "Earnings" },
    { href: HOST_PATHS.payouts, label: "Payouts" },
    { href: HOST_PATHS.help, label: "Help" },
  ],
  phoneDestinations: [
    { href: HOST_PATHS.home, label: "Home", icon: House },
    { href: HOST_PATHS.laptops, label: "Laptops", icon: Laptop },
    { href: HOST_PATHS.earnings, label: "Earnings", icon: Wallet },
    { href: HOST_PATHS.payouts, label: "Payouts", icon: Landmark },
    { href: HOST_PATHS.help, label: "Help", icon: LifeBuoy },
  ],
  // A laptop's own screens, adding one and the sessions it ran are under Laptops; payout details under Payouts.
  isCurrent: (pathname, href) =>
    href === HOST_PATHS.laptops
      ? [HOST_PATHS.laptops, HOST_PATHS.laptop, HOST_PATHS.add, HOST_PATHS.sessions].some((at) =>
          pathname.startsWith(at),
        )
      : href === HOST_PATHS.payouts
        ? pathname.startsWith(HOST_PATHS.payouts) || pathname.startsWith(HOST_PATHS.payoutDetails)
        : currentByPrefix(pathname, href),
  isFlow: (pathname) =>
    pathname.startsWith(HOST_PATHS.laptop) ||
    pathname.startsWith(HOST_PATHS.link) ||
    pathname === HOST_PATHS.add,
  accountSections: [
    { href: "/account/", label: "Profile and notifications" },
    {
      href: HOST_PATHS.payoutDetails,
      label: "Payout details",
      detail: "Your PAN and the UPI ID payouts go to",
    },
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
