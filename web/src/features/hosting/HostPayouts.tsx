import { Link } from "wouter";
import { Page } from "@/components/shell/AppShell";
import { KeyValueList } from "@/components/ui/KeyValues";
import { PageTitle, SectionHeader } from "@/components/ui/Headers";
import { FIT_FRAME } from "@/components/shell/Layout";
import { useAfterPaint } from "@/lib/after-first-screen";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { HOST_PATHS } from "@/sites/host/paths";
import { usePayoutDetails } from "@/api/payouts";
import { maskedPan } from "@/lib/pan";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { useEarnings } from "@/api/hosting";
import { BarsSkeleton } from "@/components/ui/Bars";
import { PayoutHistory, PayoutsTile, StatementTile } from "./Payouts";
import { WeeksBars } from "./WeekBars";

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
          <PageTitle>Payouts</PageTitle>
          <Link href={HOST_PATHS.payoutDetails} className="text-small font-semibold underline">
            Payout details
          </Link>
        </div>
        <div className="grid items-start gap-4 xl:grid-cols-5">
          <div className="flex flex-col gap-4 xl:col-span-2">
            <PayoutsTile enabled={painted} />
            <WhereTile enabled={painted} />
            <StatementTile enabled={painted} />
          </div>
          <div className="flex flex-col gap-4 xl:col-span-3">
            <WeeksOnTheWay enabled={painted} />
            <PayoutHistory enabled={painted} />
          </div>
        </div>
      </div>
    );
  }
  return (
    <Page title="Payouts">
      <PayoutsTile enabled={painted} />
      <WhereTile enabled={painted} />
      <StatementTile enabled={painted} />
      {/* Last: as long as the payouts are, so it arriving moves nothing above it. */}
      <PayoutHistory enabled={painted} />
    </Page>
  );
}

/** The last 8 weeks earned (ADR 0058): what payouts are made of, week by week. */
function WeeksOnTheWay({ enabled }: { enabled: boolean }) {
  const earnings = useEarnings(false, enabled);
  const owner = earnings.data?.pages[0];
  return (
    <Tile as="section" aria-labelledby="weeks-paid-title" className="flex flex-col gap-2 xl:p-8">
      <SectionHeader id="weeks-paid-title">Earned, the last 8 weeks</SectionHeader>
      <div className="h-18">
        {owner ? <WeeksBars weeks={owner.weeks} tone="panel" /> : <BarsSkeleton count={8} tone="panel" />}
      </div>
    </Tile>
  );
}

/** Where payouts go (ADR 0040): the PAN and the UPI ID on file, as Payout details shows them, and the way to change them. */
function WhereTile({ enabled }: { enabled: boolean }) {
  const details = usePayoutDetails(enabled).data;
  const rows = details
    ? [
        {
          label: "PAN",
          value: details.pan
            ? `${maskedPan(details.pan.last4)} · ${panWords(details.pan.state)}`
            : "Not given yet",
        },
        { label: "UPI ID", value: details.inUse ? details.inUse.vpa : "None in use yet" },
      ]
    : null;
  return (
    <Tile as="section" aria-labelledby="where-title" className="flex flex-col gap-2 xl:p-8">
      <SectionHeader
        id="where-title"
        action={
          <Link href={HOST_PATHS.payoutDetails} className="text-small font-semibold underline">
            Change
          </Link>
        }
      >
        Where payouts go
      </SectionHeader>
      <KeyValueList
        layout="split"
        divided
        items={(
          rows ?? [
            { label: "PAN", value: null },
            { label: "UPI ID", value: null },
          ]
        ).map((row) => [row.label, row.value ?? <SkeletonLine size="body" width="w-1/2" />])}
      />
    </Tile>
  );
}

const panWords = (state: string) =>
  state === "verified" ? "verified" : state === "rejected" ? "not accepted" : "being checked";
