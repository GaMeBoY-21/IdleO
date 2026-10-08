import { useNow } from "@/lib/clock";
import { formatDateTime, formatElapsed, formatRelative } from "@/lib/time";

/**
 * A moment, relative to now ("started 18 min ago"), with the exact time in its `datetime` and its title
 * (web/DESIGN.md "Times"). It moves on with the app's one clock, every 30 seconds.
 */
export function RelativeTime({
  value,
  prefix,
  className,
}: {
  value: string;
  prefix?: string;
  className?: string;
}) {
  const now = useNow("half-minute");
  const exact = formatDateTime(value);
  return (
    <time dateTime={value} title={exact} className={className}>
      {prefix ? `${prefix} ` : ""}
      {formatRelative(value, now)}
    </time>
  );
}

/** Time since `from`, ticking every second until `to` (when the session ended), in tabular figures. */
export function Elapsed({ from, to, className }: { from: string; to?: string | null; className?: string }) {
  return to ? (
    <span className={className ?? "tabular"}>{formatElapsed(from, to)}</span>
  ) : (
    <Ticking from={from} className={className} />
  );
}

function Ticking({ from, className }: { from: string; className?: string | undefined }) {
  const now = useNow("second");
  return <span className={className ?? "tabular"}>{formatElapsed(from, now)}</span>;
}
