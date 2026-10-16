/**
 * Where a renter can go (web/DESIGN.md "Navigation"): the floating tab bar on a phone, white pill tabs in the top bar
 * on a laptop. All sessions is a tile on Home.
 */
export const destinations = [
  { href: "/", label: "Home" },
  { href: "/book/", label: "Book" },
  { href: "/hosting/", label: "Hosting" },
  { href: "/credit/", label: "Credit" },
] as const;

export const isCurrent = (pathname: string, href: string) => {
  if (href === "/") {
    return pathname === "/";
  }
  return pathname.startsWith(href);
};

/** Screens that are flows on a phone: full screen, with their own back button instead of the bars. */
export const isFlow = (pathname: string) =>
  pathname.startsWith("/book") ||
  pathname === "/session/" ||
  // A machine and what you do with it are flows; the Hosting tab and its earnings keep the bars.
  (pathname.startsWith("/hosting/") && pathname !== "/hosting/" && pathname !== "/hosting/earnings/");
