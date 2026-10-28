import type { ReactNode } from "react";
import { Link } from "wouter";
import { brand } from "@/brand/brand";
import { Wordmark } from "@/brand/Wordmark";
import { UserMenuButton } from "@/components/shell/UserMenuButton";
import { FRAME } from "@/components/shell/Layout";
import { buttonClasses } from "@/components/ui/Button";
import { PageHeading } from "@/components/ui/Heading";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { useTitle } from "@/lib/title";

/**
 * The frame around an account that is suspended or banned (ADR 0038). It is told the same either way, and it has nothing
 * to book, upload or host with, so there are no tabs, no credit and no bell, only the wordmark and the avatar's menu (with
 * Sign out); the three things it can still do are the screens inside it.
 */
export function HeldShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-10 bg-canvas">
        <div className={`${FRAME} flex h-16 items-center gap-6 md:h-header`}>
          <div className="flex flex-1 items-center">
            <Link href="/" className="flex min-h-touch items-center" aria-label={`${brand.name} home`}>
              <Wordmark className="md:text-headline md:tracking-tighter" />
            </Link>
          </div>
          <UserMenuButton />
        </div>
      </header>
      <main id="main" className="flex w-full flex-1 flex-col pb-8">
        {children}
      </main>
    </div>
  );
}

/**
 * What a held account sees wherever it goes: it is suspended, in plain words and without a reason (the same for a ban), and
 * what it can still do, which no suspension takes away: see and download what we hold, delete the account, and write to us.
 */
export function HeldAccount() {
  useTitle("Your account is suspended");
  return (
    <div className="w-full px-3.5 pt-2 md:px-8 md:pt-8 xl:px-10">
      <div className="mx-auto flex w-full max-w-card flex-col gap-6">
        <PageHeading>Your account is suspended</PageHeading>
        <Tile as="section" aria-labelledby="held-says" className="flex flex-col gap-4 md:p-6 xl:p-7">
          <SectionTitle id="held-says">What this means</SectionTitle>
          <p className="max-w-prose text-small text-text-2">
            You can&apos;t book, upload or host while it is. You can still see and download everything we hold
            about you, delete your account, and write to us. If you think this is a mistake, write to us and
            say so.
          </p>
          <div className="flex flex-col gap-3 md:flex-row">
            <Link href="/account/help/" className={buttonClasses("primary", true)}>
              Write to us
            </Link>
            <Link href="/account/data/" className={buttonClasses("ghost", true)}>
              Your data
            </Link>
            <Link href="/account/delete/" className={buttonClasses("ghost", true)}>
              Delete my account
            </Link>
          </div>
        </Tile>
      </div>
    </div>
  );
}
