import type { ReactNode } from "react";
import { isLive, type Session } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";

/**
 * What the renter should know while it runs, in plain words: their credit running low, and a move to another
 * host. Why it ended is the ended screen's own headline (Ended.tsx). Banners push the content down, never cover it.
 */
export function Banners({ session }: { session: Session }) {
  const banners: ReactNode[] = [];
  if (isLive(session) && session.lowCredit) {
    banners.push(
      <Banner key="low" tone="warning">
        Your credit is running low. When it runs out, the session saves a checkpoint and stops, and
        you&apos;re charged only up to then.
      </Banner>,
    );
  }
  if (session.attempt > 1) {
    const resumed = session.checkpoints.filter((checkpoint) => checkpoint.attempt < session.attempt).at(-1);
    banners.push(
      <Banner key="moved" tone="info">
        {resumed
          ? `Moved to another host and resumed from checkpoint ${resumed.seq}.`
          : "Moved to another host and started again from the beginning."}{" "}
        The lost minutes aren&apos;t charged.
      </Banner>,
    );
  }
  return banners.length > 0 ? <div className="flex flex-col gap-2">{banners}</div> : null;
}
