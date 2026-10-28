import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link } from "wouter";
import { isLive, type Session, type Wallet } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { buttonClasses } from "@/components/ui/Button";
import { PageHeading } from "@/components/ui/Heading";
import { useAfterPaint } from "@/lib/after-first-screen";
import { offeredServices, serviceOf } from "@/lib/catalog";
import { BookAgainTile, latestSetup } from "../BookAgain";
import { RunningTile } from "../RunningTile";
import { CreditPanel } from "./CreditPanel";
import { EarnPanel } from "./EarnPanel";
import { FreeGpus } from "./FreeGpus";
import { HOME } from "./LaptopHomeSkeleton";
import { RecentSessions } from "./RecentSessions";
import { ServicesTile } from "./ServicesTile";
import { useDropAnywhere } from "./useDropAnywhere";

type Catalog = components["schemas"]["TemplateList"];

/**
 * Home from 768 px (approved reference `AppHome`): the question and "Book a GPU" over the services as accent cards,
 * what is running beside the credit, the recent sessions as a table, and a last row for the free GPUs, what to book
 * again and hosting. The GPUs free and the credit's history are asked for once the screen has painted.
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
  const live = sessions.filter(isLive);
  const again = latestSetup(sessions, catalog);
  const againName = again
    ? (serviceOf(catalog, again.templateId)?.runningLabel ??
      catalog.templates.find((template) => template.id === again.templateId)?.name ??
      again.templateId)
    : "";
  return (
    <div className={HOME}>
      {banners && <div className="flex flex-col gap-2.5">{banners}</div>}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <span className="text-body text-text-3">Or drop a folder anywhere on this page</span>
          <PageHeading id="question">What do you want to run?</PageHeading>
        </div>
        <Link href="/book/" className={buttonClasses("primary", false, true)}>
          Book a GPU
        </Link>
      </div>
      <ServicesTile services={services} catalog={catalog} dropping={dropping} />
      <div className="grid cols-panels gap-4">
        {live.map((session) => (
          <RunningTile key={session.id} session={session} catalog={catalog} wide />
        ))}
        <CreditPanel wallet={wallet} enabled={painted} />
      </div>
      <RecentSessions sessions={sessions} catalog={catalog} />
      <div className="grid cols-cards gap-4">
        <FreeGpus enabled={painted} />
        {again && <BookAgainTile session={again} name={againName} />}
        <EarnPanel enabled={painted} />
      </div>
    </div>
  );
}
