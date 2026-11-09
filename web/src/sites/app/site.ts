import { Activity, FolderOpen, House, Wallet, Zap } from "lucide-react";
import { hostLink } from "@/lib/site-links";
import { currentByPrefix, type SiteNav } from "../types";

/**
 * The renter app's bars (web/DESIGN.md "Navigation"): the sidebar from 768 px lists all five, and the phone's tab bar the
 * same five with "More" for the rest (Account, signing out). No hosting here (ADR 0058): the host site is one quiet link
 * in the account menu.
 */
export const nav: SiteNav = {
  destinations: [
    { href: "/", label: "Home" },
    { href: "/book/", label: "Book a GPU" },
    { href: "/sessions/", label: "Sessions" },
    { href: "/files/", label: "My files" },
    { href: "/credit/", label: "Credit" },
  ],
  phoneDestinations: [
    { href: "/", label: "Home", icon: House },
    { href: "/book/", label: "Book", icon: Zap },
    { href: "/sessions/", label: "Sessions", icon: Activity },
    { href: "/files/", label: "Files", icon: FolderOpen },
    { href: "/credit/", label: "Credit", icon: Wallet },
  ],
  isCurrent: (pathname, href) =>
    href === "/sessions/" ? pathname.startsWith("/session") : currentByPrefix(pathname, href),
  isFlow: (pathname) => pathname.startsWith("/book") || pathname === "/session/",
  accountSections: [
    { href: "/account/", label: "Profile and notifications" },
    { href: "/account/keys/", label: "API keys", detail: "For the command line, scripts and the SDK" },
    {
      href: "/account/data/",
      label: "Your data",
      detail: "What we hold, download it, or delete your account",
    },
    { href: "/account/help/", label: "Help", detail: "Write to us, and read our answers" },
    {
      href: "/account/images/",
      label: "My images",
      detail: "Your own container images, and what their checks found",
    },
    { href: "/account/reports/", label: "Reports", detail: "Problems you've reported, and what we decided" },
  ],
  menuItems: [],
  otherSite: { href: hostLink("/"), label: "Earn with your laptop" },
};
