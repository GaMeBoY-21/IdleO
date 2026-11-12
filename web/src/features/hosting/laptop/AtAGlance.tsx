import { useEarnings, type Host } from "@/api/hosting";
import { DataCell, DataRow, DataTable } from "@/components/ui/DataTable";
import { Money } from "@/components/ui/Money";
import { StatusChip } from "@/components/ui/StatusChip";
import { SectionHeader } from "@/components/ui/Headers";
import { Tile } from "@/components/ui/Tile";
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
    <Tile as="section" aria-labelledby="glance-title" className="flex flex-col gap-2 xl:p-8">
      <SectionHeader id="glance-title">At a glance</SectionHeader>
      <DataTable
        caption="Every laptop at a glance"
        columns={[
          { label: "Laptop" },
          { label: "State" },
          { label: "GPU" },
          { label: "An hour", numeric: true },
          { label: "Online" },
          { label: "Earned in all", numeric: true },
        ]}
      >
        {machines.map((host) => {
          const state = machineState(host, now);
          const gpu = host.gpus.find((candidate) => !candidate.gone);
          return (
            <DataRow key={host.id}>
              <DataCell className="font-semibold break-words">{host.name}</DataCell>
              <DataCell>
                <StatusChip
                  size="sm"
                  domain="machine"
                  state={state.kind}
                  label={state.kind === "paused" ? state.detail : state.label}
                />
              </DataCell>
              <DataCell>{gpu ? `${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}` : "—"}</DataCell>
              <DataCell numeric>
                {host.listing ? rupeesAnHour(host.listing.batchPricePaisePerMin) : "—"}
              </DataCell>
              <DataCell>
                {host.online && host.onlineSince ? <RelativeTime value={host.onlineSince} /> : "Offline"}
              </DataCell>
              <DataCell numeric>
                {earned.has(host.id) ? <Money paise={earned.get(host.id)!} /> : "…"}
              </DataCell>
            </DataRow>
          );
        })}
      </DataTable>
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
    <Tile as="section" aria-labelledby="needs-title" className="flex flex-col gap-1 xl:col-span-3 xl:p-8">
      <SectionHeader id="needs-title">Your laptops</SectionHeader>
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
