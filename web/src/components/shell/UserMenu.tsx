import { Check, FolderOpen, LogOut, Monitor, Moon, Sun, User } from "lucide-react";
import { DropdownMenu } from "radix-ui";
import { useState } from "react";
import { Link } from "wouter";
import { signOut, useAuth } from "@/auth";
import { applyThemeChoice, readThemeChoice, type ThemeChoice } from "@/design/theme";
import { cn } from "@/lib/cn";
import { Avatar } from "./UserMenuButton";

const themeChoices: { value: ThemeChoice; label: string; icon: typeof Sun }[] = [
  { value: "system", label: "Match my device", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
];

const item =
  "flex min-h-touch cursor-default items-center gap-3 rounded-control px-3 text-small outline-none select-none data-highlighted:bg-ground";

/** Who's signed in, My files, the theme switch, and signing out. */
export function UserMenu({ defaultOpen = false }: { defaultOpen?: boolean }) {
  const auth = useAuth();
  const [theme, setTheme] = useState<ThemeChoice>(readThemeChoice);

  const email = auth.status === "signed-in" ? auth.user.email : null;

  return (
    <DropdownMenu.Root modal={false} defaultOpen={defaultOpen}>
      <DropdownMenu.Trigger
        className="flex size-touch items-center justify-center rounded-full"
        aria-label="Your account and settings"
      >
        <Avatar />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={4}
          data-tile=""
          className="appear z-20 w-menu rounded-tile bg-fill p-2 shadow-float"
        >
          {email && (
            <DropdownMenu.Label className="truncate px-3 py-2 text-caption text-text-muted">
              Signed in as {email}
            </DropdownMenu.Label>
          )}
          <DropdownMenu.Separator className="my-1 h-px bg-divider" />
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
          <DropdownMenu.Separator className="my-1 h-px bg-divider" />
          <DropdownMenu.Label className="px-3 pt-2 pb-1 text-caption font-medium text-text-muted">
            Theme
          </DropdownMenu.Label>
          <DropdownMenu.RadioGroup
            value={theme}
            onValueChange={(value) => {
              const choice = value as ThemeChoice;
              setTheme(choice);
              applyThemeChoice(choice);
            }}
          >
            {themeChoices.map(({ value, label, icon: Icon }) => (
              <DropdownMenu.RadioItem key={value} value={value} className={item}>
                <Icon aria-hidden="true" className="size-5" strokeWidth={1.75} />
                <span className="flex-1">{label}</span>
                <DropdownMenu.ItemIndicator>
                  <Check aria-hidden="true" className="size-4" strokeWidth={1.75} />
                </DropdownMenu.ItemIndicator>
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
          <DropdownMenu.Separator className="my-1 h-px bg-divider" />
          <DropdownMenu.Item className={cn(item, "text-danger")} onSelect={() => void signOut()}>
            <LogOut aria-hidden="true" className="size-5" strokeWidth={1.75} />
            Sign out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
