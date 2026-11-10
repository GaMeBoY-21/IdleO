import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { itemsOf, useInbox } from "@/api/inbox";
import { Page } from "@/components/shell/AppShell";
import { AppLink } from "@/components/ui/AppLink";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { RelativeTime } from "@/components/ui/Time";
import { useAfterPaint } from "@/lib/after-first-screen";
import { siteLink } from "@/lib/site-links";
import { useTitle } from "@/lib/title";
import { HELP_VIRTUALIZATION } from "@/site/paths";
import { DownloadButton } from "./AddMachine";

const ROW = "flex min-h-touch items-center gap-3 py-3 text-body hover:underline";

/** What a host most often needs to fix, each to the page that explains it. */
const TROUBLES = [
  { href: siteLink(`${HELP_VIRTUALIZATION}other/`), label: "The app says virtualization is off" },
  { href: siteLink("/docs/hosting/"), label: "The host guide: what runs, what it costs you, what you earn" },
  { href: "/account/help/", label: "Write to us, and read our answers" },
] as const;

/**
 * The host site's Help tab (ADR 0058): the Windows app's download, what to do when something's wrong, and the latest
 * notices. Writing to us is the account's Help, as on the renter app.
 */
export function HostHelp() {
  useTitle("Help");
  const painted = useAfterPaint();
  const inbox = useInbox(painted);
  const latest = itemsOf(inbox.data).slice(0, 3);
  return (
    <Page title="Help">
      <Tile as="section" aria-labelledby="app-title" className="flex flex-col gap-3 md:p-6">
        <SectionTitle id="app-title">The Windows app</SectionTitle>
        <p className="text-small text-text-2">
          It runs renters&apos; jobs in a sealed sandbox, pauses the moment you use the laptop, and updates by
          itself.
        </p>
        <DownloadButton />
      </Tile>
      <Tile as="section" aria-labelledby="trouble-title" className="flex flex-col gap-1 md:p-6">
        <SectionTitle id="trouble-title">When something&apos;s wrong</SectionTitle>
        <ul className="flex flex-col divide-y divide-line">
          {TROUBLES.map((trouble) => (
            <li key={trouble.href}>
              <AppLink href={trouble.href} className={ROW}>
                <span className="flex-1">{trouble.label}</span>
                <ChevronRight aria-hidden="true" className="size-5 text-text-2" strokeWidth={1.75} />
              </AppLink>
            </li>
          ))}
        </ul>
      </Tile>
      <Tile as="section" aria-labelledby="notices-title" className="flex flex-col gap-1 md:p-6">
        <SectionTitle
          id="notices-title"
          action={
            <Link href="/notifications/" className="text-small font-semibold underline">
              All notices
            </Link>
          }
        >
          Notices
        </SectionTitle>
        {inbox.data ? (
          latest.length === 0 ? (
            <p className="min-h-touch content-center text-small text-text-2">
              Nothing new. When a laptop goes offline or a payout is made, it&apos;s here and in your email.
            </p>
          ) : (
            <ul className="flex flex-col divide-y divide-line">
              {latest.map((item) => (
                <li key={item.id}>
                  <AppLink href={item.link} site={item.site} className={ROW}>
                    <span className="min-w-0 flex-1 truncate">{item.title}</span>
                    <RelativeTime value={item.createdAt} className="shrink-0 text-small text-text-2" />
                  </AppLink>
                </li>
              ))}
            </ul>
          )
        ) : (
          <div role="status" aria-label="Loading your notices" className="flex flex-col gap-2 py-3">
            <SkeletonLine size="body" width="w-3/4" />
            <SkeletonLine size="body" width="w-1/2" />
          </div>
        )}
      </Tile>
    </Page>
  );
}
