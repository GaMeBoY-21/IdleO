import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link } from "wouter";
import { isLive, type Session, type Wallet } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { buttonClasses } from "@/components/ui/Button";
import { PageHeading } from "@/components/ui/Heading";
import { useAfterPaint } from "@/lib/after-first-screen";
import { offeredServices, serviceOf, serviceTypicalCost } from "@/lib/catalog";
import { useTypicalPrices } from "@/api/home";
import { BookAgainTile, latestSetup } from "../BookAgain";
import { RunningTile } from "../RunningTile";
import { FirstRun } from "../FirstRun";
import { CreditPanel } from "./CreditPanel";
import { DevelopersPanel } from "./DevelopersPanel";
import { FreeGpus } from "./FreeGpus";
import { HOME } from "./LaptopHomeSkeleton";
import { RecentSessions } from "./RecentSessions";
import { ServicesTile } from "./ServicesTile";
import { UsagePanel } from "./UsagePanel";
import { useDropAnywhere } from "./useDropAnywhere";

type Catalog = components["schemas"]["TemplateList"];

/**
 * Home from 768 px (approved reference `AppHome`, ADR 0058's "full and simple"): the question and "Book a GPU" over the
 * services as accent cards, each with its typical time and cost; then a row of three: the credit, the GPUs online by
 * size and this month's use; what is running and the recent sessions; and a last row for a new renter's checklist, what
 * to book again and the terminal. Everything after the services is asked for once the screen has painted.
 */
export function LaptopHome({
  catalog,
  sessions,
  wallet,
  banners,
}: {
  catalog: Catalog;
  sessions: Session[];
  wallet: UseQueryResult<Wallet>;
  banners: ReactNode;
}) {
  const painted = useAfterPaint();
  const services = offeredServices(catalog);
  const dropping = useDropAnywhere(services, true);
  const prices = useTypicalPrices(painted);
  const live = sessions.filter(isLive);
  const again = latestSetup(sessions, catalog);
  const againName = again
    ? (serviceOf(catalog, again.templateId)?.runningLabel ??
      catalog.templates.find((template) => template.id === again.templateId)?.name ??
      again.templateId)
    : "";
  return (
    <div className={HOME}>
      {banners && <div className="flex flex-col gap-2">{banners}</div>}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <span className="text-body text-text-3">Or drop a folder anywhere on this page</span>
          <PageHeading id="question">What do you want to run?</PageHeading>
        </div>
        <Link href="/book/" className={buttonClasses("primary", false, true)}>
          Book a GPU
        </Link>
      </div>
      <ServicesTile services={services} catalog={catalog} dropping={dropping} prices={prices.data?.classes} />
      <div className="grid gap-4 xl:h-home-panel xl:grid-cols-3">
        <CreditPanel wallet={wallet} enabled={painted} />
        <FreeGpus enabled={painted} />
        <UsagePanel enabled={painted} />
      </div>
      {live.length > 0 && (
        <div className="grid cols-panels gap-4">
          {live.map((session) => (
            <RunningTile key={session.id} session={session} catalog={catalog} wide />
          ))}
        </div>
      )}
      <RecentSessions sessions={sessions} catalog={catalog} />
      <FirstRun
        wallet={wallet}
        sessions={sessions}
        catalog={catalog}
        sampleCost={prices.data ? serviceTypicalCost(catalog, "diagnostics", prices.data.classes) : null}
      />
      <div className="grid cols-panels gap-4">
        {again && <BookAgainTile session={again} name={againName} />}
        <DevelopersPanel />
      </div>
    </div>
  );
}
