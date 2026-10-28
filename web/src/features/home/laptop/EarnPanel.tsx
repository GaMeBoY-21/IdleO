import { useEarnings, useHosts } from "@/api/hosting";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { COMMISSION_PERCENT } from "@/lib/hosting";

/**
 * Hosting, for gamers: the volt tile (the website's "For gamers" card). For someone with no machine it is
 * the pitch, with "Start hosting"; for someone who hosts it shows this week's earnings, the server's, and "Open Hosting".
 * What they host is asked for once the screen has painted (`enabled`).
 */
export function EarnPanel({ enabled }: { enabled: boolean }) {
  const hosts = useHosts(enabled);
  const hosting = (hosts.data?.hosts.length ?? 0) > 0;
  const earnings = useEarnings(false, enabled && hosting);
  const week = earnings.data?.pages[0]?.weekPaise;
  return (
    <TileLink href="/hosting/" tone="volt" size="lg" className="gap-4">
      <TileLabel tone="volt" className="font-bold tracking-wider uppercase">
        For gamers
      </TileLabel>
      {/* Top-aligned and filling the tile. Until we know whether they host nothing is drawn here, so what arrives moves
          nothing: the label above and the button below never change places. */}
      <span className="flex flex-1 flex-col gap-2">
        {hosts.data === undefined ? null : hosting ? (
          <>
            <span className="text-small text-ink">Earned this week</span>
            <span className="flex min-h-18 items-center font-title text-timer tabular">
              {week === undefined ? <Skeleton className="h-14 w-48" /> : <Money paise={week} />}
            </span>
          </>
        ) : (
          <>
            <span className="font-title text-display-card">Your GPU sits idle. Let it earn.</span>
            <span className="max-w-prose text-body text-ink">
              Rent out your gaming laptop when you&apos;re not using it. You set the price and keep{" "}
              {100 - COMMISSION_PERCENT}%.
            </span>
          </>
        )}
      </span>
      <span className="self-start rounded-control bg-ink px-5 py-3 text-small font-bold text-volt">
        {hosting ? "Open Hosting" : "Start hosting"}
      </span>
    </TileLink>
  );
}
