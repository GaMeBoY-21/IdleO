import { Search } from "lucide-react";
import { Link } from "wouter";
import { isChanging, isLive, useSessions, useTemplates, useWallet } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { TwoPanels } from "@/features/flow/TwoPanels";
import { YourSession } from "@/features/flow/YourSession";
import { offeredServices, serviceOf } from "@/lib/catalog";
import { gpuName } from "@/lib/gpu";
import { useLaptop } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { BookAgain } from "./BookAgain";
import { RunningBar } from "./RunningBar";
import { RunningPanel } from "./RunningPanel";
import { ServiceTiles, ServiceTilesSkeleton } from "./ServiceTiles";

const QUESTION = "What do you want to run?";
const NO_CATALOG = { services: [], templates: [] };

/**
 * The renter's home (docs/design/redesign/home-phone.html, designed for the phone first): a black bar for each
 * session that's live, the question, the services as tiles, and Book again. On a laptop the same, as Book's left
 * panel, with the live session's progress, or what a session is, on the right. The order is chosen in code, not by
 * CSS, so what a screen reader reads and the tab order follow what's on screen.
 */
export function Home() {
  useTitle(null);
  const sessions = useSessions();
  const templates = useTemplates();
  const laptop = useLaptop();
  // The header's credit moves while a session can still change it.
  useWallet(sessions.data?.sessions.some(isChanging) ?? false);
  const since = staleSince(sessions, templates);

  // Until the sessions and the catalog are known, Home is one skeleton: a live session arriving would push
  // everything under its bar down.
  const known = (query: { data?: unknown; isError: boolean }) => query.data !== undefined || query.isError;
  if (!known(sessions) || !known(templates)) {
    return <HomeSkeleton laptop={laptop} />;
  }

  const catalog = templates.data ?? NO_CATALOG;
  const list = sessions.data?.sessions ?? [];
  const live = list.filter(isLive);
  const templateName = (id: string) => catalog.templates.find((template) => template.id === id)?.name ?? id;

  const left = (
    <>
      {since !== null && <StaleNotice updatedAt={since} />}
      {live.some((session) => session.lowCredit) && (
        <Banner tone="warning">
          Your credit is running low. A running session stops, after a last checkpoint, when it runs out.
        </Banner>
      )}
      {live.map((session) => (
        <RunningBar key={session.id} session={session} catalog={catalog} />
      ))}
      <div className="flex flex-col gap-4">
        <h1 className={laptop ? "text-question" : "text-title"}>{QUESTION}</h1>
        {!laptop && (
          <Link
            href="/book/"
            className="flex min-h-14 items-center gap-3 rounded-control bg-fill px-4 hover:bg-fill-selected"
          >
            <Search aria-hidden="true" className="size-5.5" strokeWidth={2} />
            <span className="text-option font-semibold">Pick a template</span>
          </Link>
        )}
      </div>
      {templates.data ? (
        <ServiceTiles services={offeredServices(catalog)} laptop={laptop} />
      ) : (
        <ErrorState
          error={templates.error}
          what="what you can run"
          onRetry={() => void templates.refetch()}
          retrying={templates.isFetching}
        />
      )}
      {sessions.data ? (
        <BookAgain sessions={list} templateName={templateName} />
      ) : (
        <ErrorState
          error={sessions.error}
          what="your sessions"
          onRetry={() => void sessions.refetch()}
          retrying={sessions.isFetching}
        />
      )}
    </>
  );

  if (!laptop) {
    return <div className="flex flex-col gap-4.5 px-4 pt-1 pb-4 md:px-6 md:pt-6">{left}</div>;
  }
  const current = live[0];
  const label = current && serviceOf(catalog, current.templateId)?.runningLabel;
  return (
    <TwoPanels
      left={left}
      rightLabel="Your session"
      right={
        current ? (
          <RunningPanel
            session={current}
            title={
              current.gpu ? `${label ?? "Running"} on ${gpuName(current.gpu.model)}` : "Finding your GPU"
            }
          />
        ) : (
          <YourSession data={{ detail: "A file you upload, or a link", done: false }} runtime={null} />
        )
      }
    />
  );
}

/** Home's shape before anything is known: no words, so nothing real moves when the content replaces it. */
function HomeSkeleton({ laptop }: { laptop: boolean }) {
  const left = (
    <div role="status" aria-label="Loading" className="flex flex-col gap-4.5">
      <SkeletonLine size={laptop ? "question" : "title"} width="w-3/4" />
      {!laptop && <Skeleton className="h-14 w-full" />}
      <ServiceTilesSkeleton laptop={laptop} />
    </div>
  );
  return laptop ? (
    <TwoPanels left={left} right={null} rightLabel="Your session" />
  ) : (
    <div className="flex flex-col px-4 pt-1 pb-4 md:px-6 md:pt-6">{left}</div>
  );
}
