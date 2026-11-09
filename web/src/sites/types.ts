import type { LucideIcon } from "lucide-react";

/**
 * What the shared frame needs from a site (ADR 0058): where its bars lead, its account's sections, and its one link to
 * the other site. Each site's is src/sites/<site>/site.ts, which the build reaches as `@site/site`.
 */
export interface SiteNav {
  /** The side of the account this site is (ADR 0058); an account that hasn't unlocked it sees `locked`. */
  side: "renting" | "hosting";
  locked: { title: string; body: string };
  /** The sidebar's destinations, from 768 px. */
  destinations: ReadonlyArray<{ href: string; label: string }>;
  /** The phone's tab bar: five, then "More". */
  phoneDestinations: ReadonlyArray<{ href: string; label: string; icon: LucideIcon }>;
  /** Whether `href`'s destination is the current one. */
  isCurrent: (pathname: string, href: string) => boolean;
  /** Screens that are flows on a phone: full screen, with their own back button instead of the bars. */
  isFlow: (pathname: string) => boolean;
  /** Account's sections: the rail's on a laptop; on a phone, those with `detail` are rows on Account. */
  accountSections: ReadonlyArray<{ href: string; label: string; detail?: string }>;
  /** The user menu's items between Account and Sign out, on this site. */
  menuItems: ReadonlyArray<{ href: string; label: string; icon: LucideIcon }>;
  /** The quiet link to the other site, in the account menu ("Earn with your laptop", "Rent a GPU"). */
  otherSite: { href: string; label: string };
}

/** Whether a path is one of a destination's: Home only at `/`, the rest by their prefix. */
export function currentByPrefix(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname.startsWith(href);
}
