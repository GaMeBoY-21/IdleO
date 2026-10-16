import { Link } from "wouter";
import { isChanging, isLive, useSessions, useTemplates, useWallet } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { offeredServices, serviceOf } from "@/lib/catalog";
import { useTitle } from "@/lib/title";
import { BookAgainTile, latestSetup } from "./BookAgain";
import { CreditTile } from "./CreditTile";
import { EarnTile } from "./EarnTile";
import { RunningTile } from "./RunningTile";
import { MainServiceTile, QuestionTile, WideServiceTile } from "./ServiceTiles";

const QUESTION = "What do you want to run?";
const NO_CATALOG = { services: [], templates: [] };

/** The tile grid (web/DESIGN.md "Shapes and space"): two columns of 88 px rows on a phone, four of 112 px from 768 px. */
const GRID = "grid grid-cols-2 auto-rows-tile gap-2.5 md:grid-cols-4 md:auto-rows-tile-lg md:gap-3.5";
const FRAME =
  "mx-auto flex w-full max-w-content flex-col gap-2.5 px-3.5 pt-1 pb-4 md:gap-3.5 md:px-6 md:pt-2";

/**
 * The renter's home (docs/design/bento/home-phone.html): tiles on a grid, each a thing to check or to start. A dark
 * tile for each session that's live, the services (the first lime and tall), credit, what to book again, and hosting;
 * with nothing running, the first tile is the question, with the services inside it. The order is chosen in code, not
 * by CSS, so what a screen reader reads and the tab order follow what's on screen.
 */
export function Home() {
  useTitle(null);
  const sessions = useSessions();
  const templates = useTemplates();
  // The credit tile moves while a session can still change it.
  const wallet = useWallet(sessions.data?.sessions.some(isChanging) ?? false);
  const since = staleSince(sessions, templates);

  // Until the sessions and the catalog are known, Home is one skeleton: a live session arriving would push
  // everything under it down.
  const known = (query: { data?: unknown; isError: boolean }) => query.data !== undefined || query.isError;
  if (!known(sessions) || !known(templates)) {
    return <HomeSkeleton />;
  }

  const catalog = templates.data ?? NO_CATALOG;
  const list = sessions.data?.sessions ?? [];
  const live = list.filter(isLive);
  const services = offeredServices(catalog);
  const again = latestSetup(list);
  const againName = again
    ? (serviceOf(catalog, again.templateId)?.runningLabel ??
      catalog.templates.find((template) => template.id === again.templateId)?.name ??
      again.templateId)
    : "";

  return (
    <div className={FRAME}>
      {since !== null && <StaleNotice updatedAt={since} />}
      {live.some((session) => session.lowCredit) && (
        <Banner tone="warning">
          Your credit is running low. A running session stops, after a last checkpoint, when it runs out.
        </Banner>
      )}
      {live.length > 0 && <h1 className="sr-only">{QUESTION}</h1>}
      <div className={GRID}>
        {live.map((session) => (
          <RunningTile key={session.id} session={session} catalog={catalog} />
        ))}
        {!templates.data ? (
          <Tile className="col-span-2 row-span-2">
            <ErrorState
              error={templates.error}
              what="what you can run"
              onRetry={() => void templates.refetch()}
              retrying={templates.isFetching}
            />
          </Tile>
        ) : live.length === 0 ? (
          <QuestionTile services={services} />
        ) : (
          services[0] && <MainServiceTile service={services[0]} />
        )}
        <CreditTile wallet={wallet} wide={again === undefined} />
        {again && <BookAgainTile session={again} name={againName} />}
        {live.length > 0 &&
          services.slice(1).map((service) => <WideServiceTile key={service.id} service={service} />)}
        <EarnTile />
        {!sessions.data && (
          <Tile className="col-span-2 row-span-2">
            <ErrorState
              error={sessions.error}
              what="your sessions"
              onRetry={() => void sessions.refetch()}
              retrying={sessions.isFetching}
            />
          </Tile>
        )}
        {sessions.data && list.length > 0 && (
          <Link
            href="/sessions/"
            className="tile-motion col-span-2 hidden items-center justify-between rounded-tile bg-fill p-4.5 text-section md:flex"
          >
            All sessions
            <span aria-hidden="true">→</span>
          </Link>
        )}
      </div>
      {/* A phone has no Sessions tab: the list is one row away from Home. */}
      {sessions.data && list.length > 0 && (
        <Link
          href="/sessions/"
          className="flex min-h-touch items-center justify-center rounded-full text-small font-semibold text-text-muted hover:text-text md:hidden"
        >
          All sessions
        </Link>
      )}
    </div>
  );
}

/** Home's shape before anything is known: the tiles of a home with nothing running, with no words in them. */
function HomeSkeleton() {
  return (
    <div className={FRAME}>
      <div role="status" aria-label="Loading" className={GRID}>
        <Skeleton tile className="col-span-2 row-span-2" />
        <Skeleton tile />
        <Skeleton tile />
        <Skeleton tile className="col-span-2 row-span-2" />
      </div>
    </div>
  );
}
