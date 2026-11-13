import type { ReactNode } from "react";
import { Link } from "wouter";
import { cn } from "@/lib/cn";
import { useTitle } from "@/lib/title";

/**
 * The page header and the section header (web/DESIGN.md "Page headers"): one pattern on every page of every site.
 *
 * A page: its breadcrumbs (a page under another), its title (the page's one h1), one line on what it's for, its one
 * primary action at the top right, then its filters. It names the browser tab too, so no page sets its own. The console's
 * is `dense`: the same order, smaller type.
 */
export interface Crumb {
  label: string;
  href: string;
}

/** The way up from a page under another: each page above it, a link, then this page's own name. */
export function Breadcrumbs({ trail, current }: { trail: Crumb[]; current: string }) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-x-2 text-small text-ink-subtle">
        {trail.map((crumb) => (
          <li key={crumb.href} className="flex items-center gap-2">
            <Link
              href={crumb.href}
              className="inline-flex min-h-control items-center underline-offset-4 hover:text-ink hover:underline pointer-coarse:min-h-touch"
            >
              {crumb.label}
            </Link>
            <span aria-hidden="true" className="text-ink-tertiary">
              /
            </span>
          </li>
        ))}
        <li aria-current="page" className="max-w-full truncate text-ink" title={current}>
          {current}
        </li>
      </ol>
    </nav>
  );
}

/** The page title's sizes: a page's own, the console's denser one, and a title held by a panel. */
const TITLE = {
  page: "text-title",
  dense: "text-figure",
  display: "text-hero",
  panel: "text-section",
  "panel-lg": "text-hero",
  card: "text-section",
  section: "text-section",
} as const;

/**
 * A page's one h1, which names its tab too ("Credit · IdleO"). `PageHeader` holds it for most pages; a page whose design
 * puts its title in a panel (the live session's night panel, a laptop's) holds it there. `hidden` keeps it for screen
 * readers only, where the page's design shows no headline.
 */
export function PageTitle({
  children,
  tabTitle,
  variant = "page",
  hidden = false,
  id,
  className,
}: {
  children: ReactNode;
  tabTitle?: string | null;
  variant?: keyof typeof TITLE;
  hidden?: boolean;
  id?: string;
  className?: string;
}) {
  useTitle(tabTitle !== undefined ? tabTitle : typeof children === "string" ? children : null);
  return (
    <h1 id={id} className={hidden ? "sr-only" : cn("min-w-0 break-words", TITLE[variant], className)}>
      {children}
    </h1>
  );
}

export function PageHeader({
  title,
  tabTitle,
  description,
  trail,
  action,
  kicker,
  crumb,
  dense = false,
  hideTitle = false,
  id,
  children,
}: {
  title: ReactNode;
  /** The breadcrumbs' last step, when it isn't the title as it stands (a record's name). */
  crumb?: string;
  /** The tab's name for this page, when the title isn't plain words (a name that's still loading: null). */
  tabTitle?: string | null;
  /** One line on what the page is for. */
  description?: ReactNode;
  /** The pages above this one: breadcrumbs, ending at this page's title. */
  trail?: Crumb[];
  /** The page's one primary action, at the top right. */
  action?: ReactNode;
  /** A small line over the title ("Or drop a folder anywhere on this page"). */
  kicker?: ReactNode;
  /** The console's: the same order, its denser type. */
  dense?: boolean;
  /** For a page whose design shows its title elsewhere (a night panel's headline): still its h1, for screen readers. */
  hideTitle?: boolean;
  id?: string;
  /** The page's filters, under the title. */
  children?: ReactNode;
}) {
  const named = tabTitle !== undefined ? tabTitle : typeof title === "string" ? title : null;
  return (
    <header
      className={cn(
        "flex flex-col",
        dense ? "gap-2" : "gap-4",
        hideTitle && !trail && !children && "contents",
      )}
    >
      {trail && trail.length > 0 && <Breadcrumbs trail={trail} current={crumb ?? named ?? ""} />}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className={cn("flex min-w-0 flex-col gap-2", hideTitle && "sr-only")}>
          {kicker && <p className="text-small text-ink-subtle">{kicker}</p>}
          <PageTitle id={id} tabTitle={named} variant={dense ? "dense" : "page"}>
            {title}
          </PageTitle>
          {description && <p className="max-w-prose text-small text-ink-subtle">{description}</p>}
        </div>
        {action && <div className="flex shrink-0 flex-wrap items-center gap-2">{action}</div>}
      </div>
      {children}
    </header>
  );
}

/**
 * A section's header: its title (an h2, or an h3 under another section), one line on it, and its action at the end.
 * A panel's title is one too.
 */
export function SectionHeader({
  children,
  description,
  action,
  id,
  level = 2,
  className,
}: {
  children: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  id?: string;
  level?: 2 | 3;
  className?: string;
}) {
  const Heading = level === 2 ? "h2" : "h3";
  return (
    <div className={cn("flex min-h-control items-center justify-between gap-4", className)}>
      <div className="flex min-w-0 flex-col gap-1">
        <Heading id={id} className={level === 2 ? "text-section" : "text-card"}>
          {children}
        </Heading>
        {description && <p className="max-w-prose text-small text-ink-subtle">{description}</p>}
      </div>
      {action}
    </div>
  );
}
