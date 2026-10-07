import { isChanging, isLive, useSessions, useWallet } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { useTitle } from "@/lib/title";
import { CreditCard } from "./CreditCard";
import { SessionsCard } from "./SessionsCard";

/** The renter's home: their credit and their sessions, live from the API (web/DESIGN.md). */
export function Home() {
  useTitle(null);
  const sessions = useSessions();
  const wallet = useWallet(sessions.data?.sessions.some(isChanging) ?? false);
  const lowCredit = sessions.data?.sessions.some((session) => isLive(session) && session.lowCredit) ?? false;

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-heading font-semibold">Home</h1>
      {lowCredit && (
        <Banner tone="warning">
          Your credit is running low. A running session stops, after a last checkpoint, when it runs out.
        </Banner>
      )}
      <div className="grid gap-4 xl:grid-cols-12">
        <div className="min-w-0 xl:col-span-4">
          <CreditCard wallet={wallet} />
        </div>
        <div className="min-w-0 xl:col-span-8">
          <SessionsCard sessions={sessions} />
        </div>
      </div>
    </div>
  );
}
