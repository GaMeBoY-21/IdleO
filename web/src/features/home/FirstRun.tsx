import type { UseQueryResult } from "@tanstack/react-query";
import { useCachedMe, type Session, type Wallet } from "@/api/queries";
import { Checklist, type Step } from "@/components/ui/Checklist";
import { bookHref } from "@/lib/catalog";

/** The CUDA test: the job a new renter runs first, which proves a GPU reaches their container. */
export const FIRST_JOB = bookHref("diagnostics", "cuda-smoke-test");

/**
 * A new renter's first run (ADR 0058): accept the Terms, have credit (the free credit counts), run a first test job.
 * Shown on Home until all three are done; each is read from what the account already has, never stored apart.
 */
export function FirstRun({
  wallet,
  sessions,
}: {
  wallet: UseQueryResult<Wallet>;
  sessions: Session[] | undefined;
}) {
  const me = useCachedMe();
  const steps: Step[] = [
    {
      title: "Accept the Terms",
      detail: "The Terms and the privacy notice, once.",
      done: me ? me.accepted : null,
      action: { href: "/agree/", label: "Read and accept" },
    },
    {
      title: "Have credit to spend",
      detail: "Your free credit counts. Top up by UPI when you want more.",
      done: wallet.data ? wallet.data.availablePaise > 0 || (sessions?.length ?? 0) > 0 : null,
      action: { href: "/credit/", label: "Top up" },
    },
    {
      title: "Run a first test job",
      detail: "The CUDA test runs nvidia-smi on a GPU and stops, proving a GPU reaches your job.",
      done: sessions ? sessions.some((session) => session.state === "COMPLETED") : null,
      action: { href: FIRST_JOB, label: "Run the test" },
    },
  ];
  return <Checklist id="first-run-title" title="Get started" steps={steps} />;
}
