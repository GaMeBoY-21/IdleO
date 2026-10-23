import type { AnchorHTMLAttributes } from "react";
import { Link } from "wouter";

/**
 * The pages that open as a page load of their own, never as a step inside another: the Credit page carries a security
 * policy of its own that lets Razorpay's Checkout run (ADR 0039), and a policy belongs to the page that loaded.
 */
export const OWN_PAGE_LOADS: readonly string[] = ["/credit/"];

/** A link within the app: wouter's, except to a page that loads on its own, which gets a plain link. */
export function AppLink({
  href,
  children,
  ...props
}: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  const path = href.split(/[?#]/)[0];
  if (path !== undefined && OWN_PAGE_LOADS.includes(path)) {
    return (
      <a href={href} {...props}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} {...props}>
      {children}
    </Link>
  );
}
