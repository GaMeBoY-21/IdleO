import type { ReactNode } from "react";
import { Link, useLocation } from "wouter";
import { signOut } from "@/auth";
import { useMe } from "@/api/queries";
import { AppearanceChoice } from "@/components/shell/AppearanceChoice";
import { RailColumns } from "@/components/shell/Layout";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

const SECTIONS = [
  { href: "/account/", label: "Profile and notifications" },
  { href: "/account/keys/", label: "API keys" },
  { href: "/files/", label: "My files" },
  { href: "/account/reports/", label: "Reports" },
  { href: "/account/data/", label: "Your data" },
  { href: "/account/help/", label: "Help" },
] as const;

/** Account's laptop frame: the 320 px rail (the person, the sections, appearance, Sign out) and the screen beside it. */
export function AccountRail({
  title,
  children,
  className,
}: {
  title: string;
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
          <AppearanceChoice />
          <button
            type="button"
            onClick={() => void signOut()}
            className="min-h-12 rounded-full bg-fill text-small font-bold hover:bg-fill-selected"
          >
            Sign out
          </button>
        </>
      }
      right={
        <div className={cn("flex flex-col gap-4 2xl:gap-5", className)}>
          <h1 className="sr-only">{title}</h1>
          {children}
        </div>
      }
    />
  );
}

/** Who this is: the dark tile of the rail. The shapes are drawn first, so the name arriving moves nothing. */
function ProfileTile() {
  const me = useMe();
  const name = me.data ? (me.data.displayName ?? me.data.email ?? "You") : null;
  return (
    <Tile tone="dark" size="lg" aria-label="You" className="flex flex-col gap-3.5">
      <span
        aria-hidden="true"
        className="flex size-16 items-center justify-center rounded-full bg-live text-headline font-extrabold text-on-colour"
      >
        {(name ?? me.data?.email ?? " ").charAt(0).toUpperCase()}
      </span>
      {name === null ? (
        <span role="status" aria-label="Loading your account" className="flex flex-col">
          <Skeleton className="skeleton-dark h-7 w-1/2" />
          <Skeleton className="skeleton-dark h-5 w-3/4" />
        </span>
      ) : (
        <span className="flex flex-col">
          <span className="min-h-7 text-section break-words">{name}</span>
          <span className="min-h-5 text-small break-words text-dark-muted">{me.data?.email}</span>
        </span>
      )}
    </Tile>
  );
}

function SectionNav() {
  const [pathname] = useLocation();
  return (
    <Tile as="div" className="p-2.5">
      <nav aria-label="Account" className="flex flex-col gap-0.5">
        {SECTIONS.map(({ href, label }) => {
          const current = pathname === href || (href === "/account/" && pathname === "/account");
          return (
            <Link
              key={href}
              href={href}
              aria-current={current ? "page" : undefined}
              className={cn(
                "flex min-h-touch items-center rounded-control px-4 text-body",
                current ? "bg-ground font-bold" : "font-semibold text-text-muted hover:text-text",
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
