import { PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { Link, useLocation } from "wouter";
import { brand } from "@/brand/brand";
import { BrandMark, Wordmark } from "@/brand/Wordmark";
import { AppLink } from "@/components/ui/AppLink";
import { cn } from "@/lib/cn";
import { nav } from "@site/site";
import { AccountLink } from "./AccountLink";
import { toggleSidebar, useSidebarCollapsed } from "./sidebar-state";

/**
 * The site's left sidebar, from 768 px (web/DESIGN.md "Navigation"): 240 px on surface-1 with a hairline on its right,
 * collapsible to its icons. The brand mark, the site's destinations (each with the icon its phone tab has), and the
 * collapse button at the foot. The current destination goes up a step of the ladder.
 */
export function Sidebar() {
  const [pathname] = useLocation();
  const collapsed = useSidebarCollapsed();
  const icons = new Map(nav.phoneDestinations.map((destination) => [destination.href, destination.icon]));
  return (
    <aside
      className={cn(
        "fixed inset-y-0 left-0 z-raised hidden flex-col gap-1 border-r border-hairline bg-surface-1 p-2 md:flex",
        collapsed ? "w-sidebar-icons" : "w-sidebar",
      )}
    >
      <Link
        href="/"
        className={cn("flex h-header items-center px-2 text-live", collapsed && "justify-center")}
        aria-label={`${brand.name} home`}
      >
        {collapsed ? <BrandMark className="size-6" /> : <Wordmark className="text-section" />}
      </Link>
      <nav aria-label="Main" className="flex flex-col gap-1">
        {nav.destinations.map(({ href, label }) => {
          const current = nav.isCurrent(pathname, href);
          const Icon = icons.get(href);
          return (
            <AppLink
              key={href}
              href={href}
              aria-current={current ? "page" : undefined}
              aria-label={collapsed ? label : undefined}
              title={collapsed ? label : undefined}
              className={cn(
                "flex min-h-control items-center gap-2 rounded-control px-2 text-button transition-colors",
                collapsed && "justify-center",
                current ? "bg-surface-3 text-ink" : "text-ink-subtle hover:bg-surface-2 hover:text-ink",
              )}
            >
              {Icon && <Icon aria-hidden="true" className="size-4 shrink-0" strokeWidth={1.75} />}
              {!collapsed && label}
            </AppLink>
          );
        })}
      </nav>
      <span className="flex-1" />
      <AccountLink collapsed={collapsed} />
      <button
        type="button"
        onClick={toggleSidebar}
        aria-label={collapsed ? "Expand the sidebar" : "Collapse the sidebar"}
        aria-pressed={collapsed}
        className={cn(
          "flex min-h-control items-center gap-2 rounded-control px-2 text-button text-ink-subtle hover:bg-surface-2 hover:text-ink",
          collapsed && "justify-center",
        )}
      >
        {collapsed ? (
          <PanelLeftOpen aria-hidden="true" className="size-4" strokeWidth={1.75} />
        ) : (
          <>
            <PanelLeftClose aria-hidden="true" className="size-4" strokeWidth={1.75} />
            Collapse
          </>
        )}
      </button>
    </aside>
  );
}
