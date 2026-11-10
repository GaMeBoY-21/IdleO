import { useEarnings, type Host } from "@/api/hosting";
import { Money } from "@/components/ui/Money";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { useNow } from "@/lib/clock";
import { gigabytes, gpuName } from "@/lib/gpu";
import { machineState, rupeesAnHour } from "@/lib/hosting";
import { laptopPath } from "@/sites/host/paths";

/**
 * Every laptop at a glance (ADR 0058, the Laptops tab): its state, GPU, price, how long it has been online and what it has
 * earned in all, side by side, from the laptops already on the screen and the earnings, asked for once it has painted.
 */
export function AtAGlance({ machines, enabled }: { machines: Host[]; enabled: boolean }) {
  const now = useNow("half-minute");
  const earnings = useEarnings(false, enabled);
  const earned = new Map(
    earnings.data?.pages[0]?.machines.map((machine) => [machine.hostId, machine.earnedPaise]),
  );
  return (
    <Tile as="section" aria-labelledby="glance-title" className="flex flex-col gap-2 xl:p-7">
      <SectionTitle id="glance-title">At a glance</SectionTitle>
      <table className="w-full text-left text-small">
        <thead className="text-caption text-text-2">
          <tr>
            <th className="py-2 font-semibold">Laptop</th>
            <th className="py-2 font-semibold">State</th>
            <th className="py-2 font-semibold">GPU</th>
            <th className="py-2 font-semibold">An hour</th>
            <th className="py-2 font-semibold">Online</th>
            <th className="py-2 text-right font-semibold">Earned in all</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {machines.map((host) => {
            const state = machineState(host, now);
            const gpu = host.gpus.find((candidate) => !candidate.gone);
            return (
              <tr key={host.id} className="h-touch">
                <td className="py-2 font-semibold break-words">{host.name}</td>
                <td className="py-2">{state.kind === "paused" ? state.detail : state.label}</td>
                <td className="py-2">{gpu ? `${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}` : "—"}</td>
                <td className="py-2 tabular">
                  {host.listing ? rupeesAnHour(host.listing.batchPricePaisePerMin) : "—"}
                </td>
                <td className="py-2">
                  {host.online && host.onlineSince ? <RelativeTime value={host.onlineSince} /> : "Offline"}
                </td>
                <td className="py-2 text-right tabular">
                  {earned.has(host.id) ? <Money paise={earned.get(host.id)!} /> : "…"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Tile>
  );
}

/**
 * What each laptop needs, if anything (ADR 0058, the Help tab): offline, an update, a self-test to pass, or nothing, in
 * plain words, each opening the laptop.
 */
export function WhatEachNeeds({ machines }: { machines: Host[] | undefined }) {
  const now = useNow("half-minute");
  return (
    <Tile as="section" aria-labelledby="needs-title" className="flex flex-col gap-1 xl:col-span-3 xl:p-7">
      <SectionTitle id="needs-title">Your laptops</SectionTitle>
      {machines && machines.length === 0 ? (
        <p className="text-small text-text-2">No laptop linked yet. Download the app above to add one.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line">
          {(machines ?? []).map((host) => {
            const state = machineState(host, now);
            const need = host.updateRequired
              ? "Its app is older than we support: it updates by itself when the laptop is on and idle."
              : !host.online
                ? "Offline: check the laptop is on, plugged in and the IdleO app is running."
                : host.status !== "active"
                  ? "Waiting for its checks: run its self-test from its page."
                  : "Nothing to do.";
            return (
              <li key={host.id} className="flex min-h-touch flex-wrap items-center gap-x-4 gap-y-1 py-2">
                <a href={laptopPath(host.id)} className="font-semibold underline">
                  {host.name}
                </a>
                <span className="text-small text-text-2">
                  {state.kind === "paused" ? state.detail : state.label}
                </span>
                <span className="text-small">{need}</span>
              </li>
            );
          })}
        </ul>
      )}
    </Tile>
  );
}
