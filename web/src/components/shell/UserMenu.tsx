import { FolderOpen, LogOut, User } from "lucide-react";
import { DropdownMenu } from "radix-ui";
import { Link } from "wouter";
import { signOut, useAuth } from "@/auth";
import { cn } from "@/lib/cn";
import { Avatar, MoreFace } from "./UserMenuButton";

const item =
  "flex min-h-touch cursor-default items-center gap-3 rounded-control px-3 text-small outline-none select-none data-highlighted:bg-raised";

/** Who's signed in, Account, My files and signing out. On a phone (`tab`) it is the tab bar's "More". */
export function UserMenu({ defaultOpen = false, tab = false }: { defaultOpen?: boolean; tab?: boolean }) {
  const auth = useAuth();

  const email = auth.status === "signed-in" ? auth.user.email : null;

  return (
    <DropdownMenu.Root modal={false} defaultOpen={defaultOpen}>
      <DropdownMenu.Trigger
        className={
          tab
            ? "flex h-full w-full flex-col items-center justify-center gap-0.5 rounded-bar text-text-2 data-[state=open]:text-text"
            : "flex size-touch items-center justify-center rounded-full"
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
          data-tile=""
          className="appear z-20 w-menu rounded-panel border border-edge bg-panel p-2 ring-1 ring-line"
        >
          {email && (
            <DropdownMenu.Label className="truncate px-3 py-2 text-caption text-text-2">
              Signed in as {email}
            </DropdownMenu.Label>
          )}
          <DropdownMenu.Separator className="my-1 h-px bg-line" />
          <DropdownMenu.Item asChild className={item}>
            <Link href="/account/">
              <User aria-hidden="true" className="size-5" strokeWidth={1.75} />
              Account
            </Link>
          </DropdownMenu.Item>
          <DropdownMenu.Item asChild className={item}>
            <Link href="/files/">
              <FolderOpen aria-hidden="true" className="size-5" strokeWidth={1.75} />
              My files
            </Link>
          </DropdownMenu.Item>
          <DropdownMenu.Separator className="my-1 h-px bg-line" />
          <DropdownMenu.Item className={cn(item, "text-danger")} onSelect={() => void signOut()}>
            <LogOut aria-hidden="true" className="size-5" strokeWidth={1.75} />
            Sign out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
