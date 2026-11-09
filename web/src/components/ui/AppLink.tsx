import type { AnchorHTMLAttributes } from "react";
import { Link } from "wouter";
import { placeOf } from "@/lib/site-links";

/**
 * The page that opens as a page load of its own, never as a step inside another: the Credit page carries a security
 * policy of its own that lets Razorpay's Checkout run (ADR 0039), and a policy belongs to the page that loaded.
 */
export const OWN_PAGE_LOAD = "/credit/";

/**
 * A link within the site: wouter's, except to the page that loads on its own, and to another site (a notice about the
 * other side of the account, ADR 0058), which get a plain link to their address.
 */
export function AppLink({
  href,
  site,
  children,
  ...props
}: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string; site?: "app" | "host" }) {
  const place = placeOf(href, site);
  // Every link to it is its bare path, so the path is compared whole.
  const Anchor = !place.here || place.href === OWN_PAGE_LOAD ? "a" : Link;
  return (
    <Anchor href={place.href} {...props}>
      {children}
    </Anchor>
  );
}
