import { config } from "@/config";
import { HOST_PATHS, MOVED_FROM_APP } from "@/sites/host/paths";
import { parseRedirects, redirectFor } from "@/sites/redirects";

/** An address on the public website (ADR 0029), which has its own address apart from the app's. */
export const siteLink = (path: string): string => `${config.siteUrl}${path}`;

/** An address on the renter app (ADR 0058), from another site. */
export const appLink = (path: string): string => `${config.appUrl}${path}`;

/** An address on the host site (ADR 0058), from another site. */
export const hostLink = (path: string): string => `${config.hostUrl}${path}`;

/** The screens every site has, at the same paths (src/sites/frame.tsx). */
const SHARED = ["/account/", "/notifications/", "/sign-in/", "/agree/", "/invite/"];
const MOVED = parseRedirects(MOVED_FROM_APP);
const HOST_OWN = Object.values(HOST_PATHS).filter((path) => path !== "/");

/**
 * Where a link leads from the site it's on (ADR 0058), for links that can belong to either site: a notice's, which
 * both sites' bells show, and which says whose path it is (`site`). One whose site isn't this one is that site's whole
 * address. Without one, a path is this site's, except, on the renter app, the old hosting addresses (which moved to the
 * host site, as older notices still name them) and, on the host site, the renter's own.
 */
export function placeOf(link: string, site?: "app" | "host"): { href: string; here: boolean } {
  if (site !== undefined) {
    return site === config.site
      ? { href: link, here: true }
      : { href: site === "host" ? hostLink(link) : appLink(link), here: false };
  }
  const [path = "/"] = link.split(/[?#]/);
  if (config.site === "app") {
    const moved = redirectFor(MOVED, path, link.slice(path.length));
    return moved ? { href: hostLink(moved), here: false } : { href: link, here: true };
  }
  const here =
    path === "/" ||
    SHARED.some((shared) => path.startsWith(shared)) ||
    HOST_OWN.some((p) => path.startsWith(p));
  return here ? { href: link, here: true } : { href: appLink(link), here: false };
}
