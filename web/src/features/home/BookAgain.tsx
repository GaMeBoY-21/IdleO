import { isLive, type Session } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { isOffered } from "@/lib/catalog";
import { TileLabel, TileLink } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { bookFrom, lastSetups } from "@/lib/sessions";
import { formatDuration } from "@/lib/time";

type Catalog = components["schemas"]["TemplateList"];

/**
 * The renter's last setup (a template, its settings and its data) from a session that has ended, as the lavender tile
 * (docs/design/bento/home-phone.html): what it was and how long it ran. A tap opens Book filled in from it. A new
 * renter has none, so the tile isn't there; older setups are one tap away, on a session's own screen.
 */
export function latestSetup(sessions: Session[], catalog: Catalog): Session | undefined {
  return lastSetups(
    sessions.filter((session) => !isLive(session) && isOffered(catalog, session.templateId)),
    1,
  )[0];
}

export function BookAgainTile({ session, name }: { session: Session; name: string }) {
  return (
    <TileLink href={bookFrom(session)} tone="ion">
      <TileLabel tone="ion">Book again</TileLabel>
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
