import { useLocation } from "wouter";
import { nav } from "@site/site";
import { NotificationBell } from "./NotificationBell";

/**
 * The 48 px bar over the content, from 768 px (web/DESIGN.md "Navigation"): where you are on the left (the current
 * destination; a page under another shows its own breadcrumbs in its header), the bell on the right (the account is the
 * sidebar's foot). Under 768 px the header and the tab bar do this.
 */
export function TopBar() {
  const [pathname] = useLocation();
  const here = nav.destinations.find(({ href }) => nav.isCurrent(pathname, href));
  return (
    <div className="sticky top-0 z-raised hidden h-header items-center justify-between gap-4 border-b border-hairline bg-canvas px-8 md:flex xl:px-10">
      <p className="truncate text-button text-ink-muted">{here?.label ?? ""}</p>
      <div className="flex items-center gap-1">
        <NotificationBell />
      </div>
    </div>
  );
}
