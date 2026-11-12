import { KeyRound } from "lucide-react";
import { Page } from "@/components/shell/AppShell";
import { Tile } from "@/components/ui/Tile";
import { useTitle } from "@/lib/title";
import { nav } from "@site/site";
import { CodeForm } from "./CodeForm";

/**
 * A site whose side of the account isn't unlocked yet (ADR 0058): an account that can rent, opening the host site, or the
 * other way round. It says so plainly and takes a code, and the other site is one link away. Never a dead end.
 */
export function Unlock() {
  useTitle(nav.locked.title);
  return (
    <Page title={nav.locked.title}>
      <Tile as="section" aria-labelledby="unlock-title" className="flex flex-col gap-4 md:p-6 xl:p-8">
        <div className="flex items-start gap-2">
          <KeyRound aria-hidden="true" className="mt-1 size-6 shrink-0" strokeWidth={1.75} />
          <div className="flex flex-col gap-1">
            <h2 id="unlock-title" className="text-section">
              Enter an invite code for {nav.side}
            </h2>
            <p className="max-w-prose text-body text-text-2">{nav.locked.body}</p>
          </div>
        </div>
        {/* The account it answers with has this side, so the screen behind this page draws at once. */}
        <CodeForm label={`Unlock ${nav.side}`} onDone={() => undefined} />
        <p className="text-small text-text-2">
          Don&apos;t have a code? Ask the person who invited you. Meanwhile,{" "}
          <a href={nav.otherSite.href} className="font-semibold underline">
            {nav.otherSite.label.toLowerCase()}
          </a>{" "}
          on your other side.
        </p>
      </Tile>
    </Page>
  );
}
