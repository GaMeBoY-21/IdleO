import { ArrowUpRight, LogOut, User } from "lucide-react";
import { DropdownMenu } from "radix-ui";
import { Link } from "wouter";
import { signOut, useAuth } from "@/auth";
import { cn } from "@/lib/cn";
import { nav } from "@site/site";
import { Avatar, MoreFace } from "./UserMenuButton";

const item =
  "flex min-h-control cursor-default items-center gap-2 rounded-control px-2 text-button outline-none select-none data-highlighted:bg-surface-3 pointer-coarse:min-h-touch";

/**
 * Who's signed in, Account, the site's own items, the one quiet link to the other site (ADR 0058) and signing out. On a
 * phone (`tab`) it is the tab bar's "More".
 */
export function UserMenu({ defaultOpen = false, tab = false }: { defaultOpen?: boolean; tab?: boolean }) {
  const auth = useAuth();

  const email = auth.status === "signed-in" ? auth.user.email : null;

  return (
    <DropdownMenu.Root modal={false} defaultOpen={defaultOpen}>
      <DropdownMenu.Trigger
        className={
          tab
            ? "flex h-full w-full flex-col items-center justify-center gap-1 text-ink-subtle data-[state=open]:text-ink"
            : "flex size-8 items-center justify-center rounded-full pointer-coarse:size-touch"
        }
        aria-label={tab ? "More" : "Your account and settings"}
      >
        {tab ? <MoreFace /> : <Avatar />}
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          side={tab ? "top" : "bottom"}
          sideOffset={tab ? 12 : 4}
          className="appear z-popover w-menu rounded-card border border-hairline-strong bg-surface-2 p-1 shadow-menu"
        >
          {email && (
            <DropdownMenu.Label className="truncate px-4 py-2 text-caption text-ink-subtle">
              Signed in as {email}
            </DropdownMenu.Label>
          )}
          <DropdownMenu.Separator className="my-1 h-px bg-hairline" />
          <DropdownMenu.Item asChild className={item}>
            <Link href="/account/">
              <User aria-hidden="true" className="size-4" strokeWidth={1.75} />
              Account
            </Link>
          </DropdownMenu.Item>
          {nav.menuItems.map(({ href, label, icon: Icon }) => (
            <DropdownMenu.Item key={href} asChild className={item}>
              <Link href={href}>
                <Icon aria-hidden="true" className="size-4" strokeWidth={1.75} />
                {label}
              </Link>
            </DropdownMenu.Item>
          ))}
          <DropdownMenu.Item asChild className={cn(item, "text-ink-subtle")}>
            <a href={nav.otherSite.href}>
              <ArrowUpRight aria-hidden="true" className="size-4" strokeWidth={1.75} />
              {nav.otherSite.label}
            </a>
          </DropdownMenu.Item>
          <DropdownMenu.Separator className="my-1 h-px bg-hairline" />
          <DropdownMenu.Item className={cn(item, "text-danger")} onSelect={() => void signOut()}>
            <LogOut aria-hidden="true" className="size-4" strokeWidth={1.75} />
            Sign out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
