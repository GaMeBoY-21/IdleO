import { Link } from "wouter";
import { Page } from "@/components/shell/AppShell";
import { FIT_FRAME } from "@/components/shell/Layout";
import { PageHeading } from "@/components/ui/Heading";
import { useAfterPaint } from "@/lib/after-first-screen";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { HOST_PATHS } from "@/sites/host/paths";
import { PayoutHistory, PayoutsTile, StatementTile } from "./Payouts";

/**
 * The host site's Payouts tab (ADR 0040, ADR 0058): what's available and pending, the next payout, the tax deducted this
 * year, every payout with its TDS and status, and the year's statement; payout details one link away. Every figure is
 * the server's, asked for after the first paint.
 */
export function HostPayouts() {
  useTitle("Payouts");
  const painted = useAfterPaint();
  const wide = useWide();
  if (wide) {
    return (
      <div className={cn(FIT_FRAME, "flex flex-col gap-6")}>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <PageHeading>Payouts</PageHeading>
          <Link href={HOST_PATHS.payoutDetails} className="text-small font-semibold underline">
            Payout details
          </Link>
        </div>
        <div className="grid items-start gap-4 xl:grid-cols-5">
          <div className="flex flex-col gap-4 xl:col-span-2">
            <PayoutsTile enabled={painted} />
            <StatementTile enabled={painted} />
          </div>
          <div className="xl:col-span-3">
            <PayoutHistory enabled={painted} />
          </div>
        </div>
      </div>
    );
  }
  return (
    <Page title="Payouts">
      <PayoutsTile enabled={painted} />
      <PayoutHistory enabled={painted} />
      <StatementTile enabled={painted} />
    </Page>
  );
}
