import { isChanging, isLive, useSessions, useTemplatesKnown, useWallet } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { StaleNotice, staleSince } from "@/components/ui/States";
import { useLaptop } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { CreditCard, CreditCardSkeleton } from "./CreditCard";
import { Card, CardTitle } from "@/components/ui/Card";
import { SessionRowsSkeleton } from "@/features/sessions/SessionRow";
import { BookAgainCard, RecentCard, RunningCard } from "./SessionsCard";

/**
 * The renter's home (web/DESIGN.md, designed for the phone first). A running session comes first, then credit, shown
 * once (the header's credit chip is hidden here), then the sessions that have ended. On a laptop: the sessions on
 * the left, the wider column, and credit and "Book again" on the right. The order is chosen in code, not by CSS, so
 * what a screen reader reads and the tab order follow what's on screen.
 */
export function Home() {
  useTitle(null);
  const sessions = useSessions();
  const wallet = useWallet(sessions.data?.sessions.some(isChanging) ?? false);
  const laptop = useLaptop();
  const list = sessions.data?.sessions ?? [];
  const live = list.filter(isLive);
  const lowCredit = live.some((session) => session.lowCredit);
  const since = staleSince(sessions, wallet);
  const namesKnown = useTemplatesKnown();

  // Until the sessions and their names are known, Home is one skeleton: a running session arriving would push the
  // credit down, and a name arriving might wrap.
  if ((sessions.data === undefined && !sessions.isError) || !namesKnown) {
    return <HomeSkeleton laptop={laptop} />;
  }

  const running = live.length > 0 && (
    <RunningCard sessions={live} updatedAt={sessions.dataUpdatedAt} stale={since !== null} />
  );
  const credit = <CreditCard wallet={wallet} />;
  const recent = <RecentCard sessions={sessions} />;
  const again = <BookAgainCard sessions={list} />;

  return (
    <div className="flex flex-col gap-4">
      <h1 className="sr-only">Home</h1>
      {since !== null && <StaleNotice updatedAt={since} />}
      {lowCredit && (
        <Banner tone="warning">
          Your credit is running low. A running session stops, after a last checkpoint, when it runs out.
        </Banner>
      )}
      {laptop ? (
        <div className="grid grid-cols-12 items-start gap-6">
          <div className="col-span-8 flex min-w-0 flex-col gap-4">
            {running}
            {recent}
          </div>
          <div className="col-span-4 flex min-w-0 flex-col gap-4">
            {credit}
            {again}
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {running}
          {credit}
          {recent}
          {again}
        </div>
      )}
    </div>
  );
}

/** Home's shape without a running session, the common case, drawn until the sessions arrive. */
function HomeSkeleton({ laptop }: { laptop: boolean }) {
  const recent = (
    <Card aria-labelledby="recent-title">
      <CardTitle id="recent-title">Recent sessions</CardTitle>
      <div role="status" aria-label="Loading your sessions">
        <SessionRowsSkeleton />
      </div>
    </Card>
  );
  return (
    <div className="flex flex-col gap-4">
      <h1 className="sr-only">Home</h1>
      {laptop ? (
        <div className="grid grid-cols-12 items-start gap-6">
          <div className="col-span-8 min-w-0">{recent}</div>
          <div className="col-span-4 min-w-0">
            <CreditCardSkeleton />
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <CreditCardSkeleton />
          {recent}
        </div>
      )}
    </div>
  );
}
