import type { AnchorHTMLAttributes } from "react";
import { Link } from "wouter";

/**
 * The page that opens as a page load of its own, never as a step inside another: the Credit page carries a security
 * policy of its own that lets Razorpay's Checkout run (ADR 0039), and a policy belongs to the page that loaded.
 */
export const OWN_PAGE_LOAD = "/credit/";

/** A link within the app: wouter's, except to the page that loads on its own, which gets a plain link. */
export function AppLink({
  href,
  children,
  ...props
}: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  // Every link to it is its bare path, so the path is compared whole.
  const Anchor = href === OWN_PAGE_LOAD ? "a" : Link;
  return (
    <Anchor href={href} {...props}>
      {children}
    </Anchor>
  );
}
