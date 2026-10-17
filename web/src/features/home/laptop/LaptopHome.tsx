import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { Session, Wallet } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { FRAME } from "@/components/shell/Layout";
import { useAfterPaint } from "@/lib/after-first-screen";
import { offeredServices } from "@/lib/catalog";
import { cn } from "@/lib/cn";
import { CreditPanel } from "./CreditPanel";
import { EarnPanel } from "./EarnPanel";
import { FreeGpus } from "./FreeGpus";
import { RecentSessions } from "./RecentSessions";
import { ServicesTile } from "./ServicesTile";
import { HOME } from "./LaptopHomeSkeleton";
import { useDropAnywhere } from "./useDropAnywhere";

type Catalog = components["schemas"]["TemplateList"];

/**
 * Home from 768 px (docs/design/bento/laptop/home-laptop.html, ADR 0031 "Laptop layout"): the services in the dark
 * tile (8 columns) with credit and the free GPUs beside it (4), the recent sessions (8) and hosting (4). The GPUs free
 * and the credit's history are asked for once the screen has painted, and each tile keeps its height, from the grid,
 * whatever has arrived.
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
  return (
    <div className="flex flex-col gap-4">
      {banners && <div className={cn(FRAME, "flex flex-col gap-2.5")}>{banners}</div>}
      <div className={HOME}>
        <ServicesTile services={services} catalog={catalog} dropping={dropping} />
        <div className="flex min-h-0 flex-col gap-4 md:col-span-6 xl:col-span-4 2xl:gap-5">
          <CreditPanel wallet={wallet} enabled={painted} />
          <FreeGpus enabled={painted} />
        </div>
        <RecentSessions sessions={sessions} catalog={catalog} />
        <EarnPanel enabled={painted} />
      </div>
    </div>
  );
}
