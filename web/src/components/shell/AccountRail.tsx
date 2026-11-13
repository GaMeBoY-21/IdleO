import type { ReactNode } from "react";
import { Link, useLocation } from "wouter";
import { PageHeader, type Crumb } from "@/components/ui/Headers";
import { signOut } from "@/auth";
import { useCachedMe, useMeAfterPaint } from "@/api/queries";
import { RailColumns } from "@/components/shell/Layout";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { nav } from "@site/site";

/** Account's laptop frame: the 320 px rail (the person, the sections, appearance, Sign out) and the screen beside it. */
export function AccountRail({
  title,
  trail,
  children,
  className,
}: {
  title: string;
  trail?: Crumb[];
  children: ReactNode;
  className?: string;
}) {
  return (
    <RailColumns
      rail="sm"
      fit={false}
      left={
        <>
          <ProfileTile />
          <SectionNav />
          <button
            type="button"
            onClick={() => void signOut()}
            className="min-h-12 rounded-control bg-surface-1 text-small font-semibold hover:bg-surface-3"
          >
            Sign out
          </button>
        </>
      }
      right={
        <div className="flex flex-col gap-4">
          <PageHeader title={title} trail={trail} />
          {/* The screen's own layout (a grid, for some) is its content's, never the header's. */}
          <div className={cn("flex flex-col gap-4 2xl:gap-4", className)}>{children}</div>
        </div>
      }
    />
  );
}

/** Who this is: the raised card of the rail. The shapes are drawn first, so the name arriving moves nothing. */
function ProfileTile() {
  const me = useMeAfterPaint();
  const kept = useCachedMe();
  const name = me.data
    ? (me.data.displayName ?? me.data.email ?? "You")
    : kept
      ? (kept.displayName ?? kept.emailName ?? "You")
      : null;
  return (
    <Tile tone="raised" size="lg" aria-label="You" className="flex flex-col gap-4">
      <span
        aria-hidden="true"
        className="flex size-16 items-center justify-center rounded-full bg-volt text-title font-semibold text-ink"
      >
        {(name ?? " ").charAt(0).toUpperCase()}
      </span>
      {name === null ? (
        <span role="status" aria-label="Loading your account" className="flex flex-col">
          <Skeleton className="skeleton h-7 w-1/2" />
          <Skeleton className="skeleton h-5 w-3/4" />
        </span>
      ) : (
        <span className="flex flex-col">
          <span className="min-h-7 text-section break-words">{name}</span>
          <span className="min-h-5 text-small break-words text-ink-subtle">{me.data?.email}</span>
        </span>
      )}
    </Tile>
  );
}

function SectionNav() {
  const [pathname] = useLocation();
  return (
    <Tile as="div" className="p-2">
      <nav aria-label="Account" className="flex flex-col gap-1">
        {nav.accountSections.map(({ href, label }) => {
          const current = pathname === href || (href === "/account/" && pathname === "/account");
          return (
            <Link
              key={href}
              href={href}
              aria-current={current ? "page" : undefined}
              className={cn(
                "flex min-h-touch items-center rounded-control px-4 text-body",
                current ? "bg-surface-3 font-semibold" : "font-semibold text-ink-subtle hover:text-ink",
              )}
            >
              {label}
            </Link>
          );
        })}
      </nav>
    </Tile>
  );
}
