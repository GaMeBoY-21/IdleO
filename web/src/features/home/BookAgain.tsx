import { isLive, type Session } from "@/api/queries";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { bookFrom, lastSetups } from "@/lib/sessions";
import { formatDuration } from "@/lib/time";

/**
 * The renter's last setup (a template, its settings and its data) from a session that has ended, as the lavender tile
 * (docs/design/bento/home-phone.html): what it was and how long it ran. A tap opens Book filled in from it. A new
 * renter has none, so the tile isn't there; older setups are one tap away, on a session's own screen.
 */
export function latestSetup(sessions: Session[]): Session | undefined {
  return lastSetups(
    sessions.filter((session) => !isLive(session)),
    1,
  )[0];
}

export function BookAgainTile({ session, name }: { session: Session; name: string }) {
  return (
    <TileLink href={bookFrom(session)} tone="lavender">
      <TileLabel tone="lavender">Book again</TileLabel>
      <span className="truncate text-small font-bold tabular">
        {name}
        {" · "}
        {session.startedAt && session.endedAt ? (
          formatDuration(session.startedAt, session.endedAt)
        ) : (
          <RelativeTime value={session.createdAt} />
        )}
      </span>
    </TileLink>
  );
}
