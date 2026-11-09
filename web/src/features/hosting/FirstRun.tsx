import { useHosts, useSelfTest } from "@/api/hosting";
import { usePayoutDetails } from "@/api/payouts";
import { useCachedMe } from "@/api/queries";
import { Checklist, type Step } from "@/components/ui/Checklist";
import { HOST_PATHS, pricePath } from "@/sites/host/paths";

/**
 * A new host's first run (ADR 0058): the Terms, the Windows app, linking the laptop, its self-test, payout details and
 * its price. Shown on the host site's home until every step is done, each read from what the account already has.
 * Hosting has no terms of its own yet: the Terms and the privacy notice are the ones a host accepts.
 */
export function FirstRun({ enabled }: { enabled: boolean }) {
  const me = useCachedMe();
  const hosts = useHosts(enabled);
  const first = hosts.data?.hosts[0];
  const selfTest = useSelfTest(first?.id ?? "", enabled && first !== undefined);
  const payouts = usePayoutDetails(enabled);
  const linked = hosts.data ? hosts.data.hosts.length > 0 : null;
  const steps: Step[] = [
    {
      title: "Accept the Terms",
      detail: "The Terms and the privacy notice, once, for renting and hosting alike.",
      done: me ? me.accepted : null,
      action: { href: "/agree/", label: "Read and accept" },
    },
    {
      title: "Download the Windows app",
      detail: "It runs renters' jobs in a sealed sandbox and pauses the moment you use the laptop.",
      done: linked,
      action: { href: HOST_PATHS.add, label: "Download" },
    },
    {
      title: "Link the laptop",
      detail: "The app opens a page here to approve it. No code to type.",
      done: linked,
      action: { href: HOST_PATHS.add, label: "How to link" },
    },
    {
      title: "Run the self-test",
      detail: "It checks the GPU, the sandbox and the connection before any job comes.",
      done:
        linked === false
          ? false
          : first === undefined
            ? null
            : selfTest.data
              ? selfTest.data.run?.state === "passed"
              : null,
      ...(first ? { action: { href: `${HOST_PATHS.laptop}?id=${first.id}`, label: "Open the laptop" } } : {}),
    },
    {
      title: "Add payout details",
      detail: "Your PAN and the UPI ID payouts go to.",
      done: payouts.data ? payouts.data.pan?.state === "verified" && payouts.data.inUse != null : null,
      action: { href: HOST_PATHS.payoutDetails, label: "Add them" },
    },
    {
      title: "Set the price and when it's available",
      detail:
        "Your price per hour, here. When it rents, in the tray: pause it, or let it pause itself when you play.",
      done: linked === false ? false : first === undefined ? null : first.listing != null,
      ...(first ? { action: { href: pricePath(first.id), label: "Set the price" } } : {}),
    },
  ];
  return <Checklist id="host-first-run-title" title="Start hosting" steps={steps} />;
}
