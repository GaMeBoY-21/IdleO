import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { PageTitle, SectionHeader } from "@/components/ui/Headers";
import { itemsOf, useInbox } from "@/api/inbox";
import { Page } from "@/components/shell/AppShell";
import { AppLink } from "@/components/ui/AppLink";
import { Tile } from "@/components/ui/Tile";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { RelativeTime } from "@/components/ui/Time";
import { useAfterPaint } from "@/lib/after-first-screen";
import { siteLink } from "@/lib/site-links";
import { useTitle } from "@/lib/title";
import { FIT_FRAME } from "@/components/shell/Layout";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { HOST_QUESTIONS } from "@/site/host-questions";
import { HELP_VIRTUALIZATION } from "@/site/paths";
import { useHosts } from "@/api/hosting";
import { DownloadButton } from "./AddMachine";
import { WhatEachNeeds } from "./laptop/AtAGlance";

const ROW = "flex min-h-touch items-center gap-2 py-4 text-body hover:underline";

/** What a host most often needs to fix, each to the page that explains it. */
const TROUBLES = [
  { href: siteLink(`${HELP_VIRTUALIZATION}other/`), label: "The app says virtualization is off" },
  { href: siteLink("/docs/hosting/"), label: "The host guide: what runs, what it costs you, what you earn" },
  { href: "/account/help/", label: "Write to us, and read our answers" },
] as const;

/** The questions hosts ask first, the earn page's own list, each opening in place. */
function Questions() {
  return (
    <Tile as="section" aria-labelledby="questions-title" className="flex flex-col gap-1 xl:col-span-3 xl:p-8">
      <SectionHeader id="questions-title">Questions hosts ask</SectionHeader>
      <div className="grid grid-cols-1 gap-x-6 xl:grid-cols-2">
        {HOST_QUESTIONS.map((item) => (
          <details key={item.question} className="border-b border-hairline py-4">
            <summary className="min-h-touch cursor-pointer content-center text-body font-semibold">
              {item.question}
            </summary>
            <p className="mt-1 text-small text-ink-subtle">{item.answer}</p>
          </details>
        ))}
      </div>
    </Tile>
  );
}

/**
 * The host site's Help tab (ADR 0058): the Windows app's download, what to do when something's wrong, and the latest
 * notices. Writing to us is the account's Help, as on the renter app.
 */
export function HostHelp() {
  useTitle("Help");
  const painted = useAfterPaint();
  const inbox = useInbox(painted);
  const hosts = useHosts(painted);
  const latest = itemsOf(inbox.data).slice(0, 3);
  const wide = useWide();
  const tiles = (
    <>
      <Tile as="section" aria-labelledby="app-title" className="flex flex-col gap-2 md:p-6">
        <SectionHeader id="app-title">The Windows app</SectionHeader>
        <p className="text-small text-ink-subtle">
          It runs renters&apos; jobs in a sealed sandbox, pauses the moment you use the laptop, and updates by
          itself.
        </p>
        <DownloadButton />
      </Tile>
      <Tile as="section" aria-labelledby="trouble-title" className="flex flex-col gap-1 md:p-6">
        <SectionHeader id="trouble-title">When something&apos;s wrong</SectionHeader>
        <ul className="flex flex-col divide-y divide-hairline">
          {TROUBLES.map((trouble) => (
            <li key={trouble.href}>
              <AppLink href={trouble.href} className={ROW}>
                <span className="flex-1">{trouble.label}</span>
                <ChevronRight aria-hidden="true" className="size-5 text-ink-subtle" strokeWidth={1.75} />
              </AppLink>
            </li>
          ))}
        </ul>
      </Tile>
      <Tile as="section" aria-labelledby="notices-title" className="flex flex-col gap-1 md:p-6">
        <SectionHeader
          id="notices-title"
          action={
            <Link href="/notifications/" className="text-small font-semibold underline">
              All notices
            </Link>
          }
        >
          Notices
        </SectionHeader>
        {inbox.data ? (
          latest.length === 0 ? (
            <p className="min-h-touch content-center text-small text-ink-subtle">
              Nothing new. When a laptop goes offline or a payout is made, it&apos;s here and in your email.
            </p>
          ) : (
            <ul className="flex flex-col divide-y divide-hairline">
              {latest.map((item) => (
                <li key={item.id}>
                  <AppLink href={item.link} site={item.site} className={ROW}>
                    <span className="min-w-0 flex-1 truncate">{item.title}</span>
                    <RelativeTime value={item.createdAt} className="shrink-0 text-small text-ink-subtle" />
                  </AppLink>
                </li>
              ))}
            </ul>
          )
        ) : (
          <div role="status" aria-label="Loading your notices" className="flex flex-col gap-2 py-4">
            <SkeletonLine size="body" width="w-3/4" />
            <SkeletonLine size="body" width="w-1/2" />
          </div>
        )}
      </Tile>
    </>
  );
  if (!wide) {
    return <Page title="Help">{tiles}</Page>;
  }
  // From 768 px the three side by side, each its own column (ADR 0058: full, and in the order a host needs them).
  return (
    <div className={cn(FIT_FRAME, "flex flex-col gap-6")}>
      <PageTitle>Help</PageTitle>
      <div className="grid grid-cols-1 items-stretch gap-4 xl:grid-cols-3">
        {tiles}
        {/* The questions are fixed; the laptops' list is as long as they are, so it comes last. */}
        <Questions />
        <WhatEachNeeds machines={hosts.data?.hosts} />
      </div>
    </div>
  );
}
