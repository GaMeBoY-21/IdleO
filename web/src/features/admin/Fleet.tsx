import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { FilterBar } from "@/components/ui/FilterBar";
import { useState } from "react";
import { StatusChip, Tag } from "@/components/ui/StatusChip";
import type { components } from "@/api/schema.gen";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { ListBar } from "./Lists";
import { ListScope, useListParams, useListView } from "./listView";
import { ActionButton } from "./permissions";
import {
  AdminPage,
  Cell,
  Empty,
  Id,
  PagedView,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

type Rollout = components["schemas"]["AdminRollout"];
type Count = components["schemas"]["AdminFleetCount"];

const COLUMNS = [
  "Host",
  "Agent",
  "Distro",
  "GPU",
  "Driver",
  "Self-tests",
  "Reconnects a day",
  "Blocklist",
  "Last seen",
  "",
];
const STEPS = ["5", "25", "50", "100"] as const;

/** A version as an admin reads it: a blocklist's, which is the moment it was published, as that date and time in India. */
const versionWords = (value: string) => (/^\d{4}-\d{2}-\d{2}T/.test(value) ? formatDateTime(value) : value);

/**
 * The fleet (ADR 0056): every host by agent, distribution, GPU and driver version, its self-tests and reconnects and the
 * blocklist it holds, on the list engine, so it filters, saves and exports; the staged rollouts of the Windows app, paused
 * at once or widened by an approved request; and a notice to the owners of the hosts listed ("update your app").
 */
export function Fleet() {
  return (
    <AdminPage title="Fleet">
      <Rollouts />
      <Summary />
      <ListScope.Provider value="fleet">
        <Hosts />
      </ListScope.Provider>
    </AdminPage>
  );
}

function Rollouts() {
  const client = useQueryClient();
  const [acting, setActing] = useState<{ rollout: Rollout; action: "pause" | "widen" } | null>(null);
  const [step, setStep] = useState<string>("25");
  const rollouts = useQuery({
    queryKey: [ADMIN, "rollouts"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/rollouts")),
    refetchInterval: 30_000,
  });
  const rollout = acting?.rollout;
  return (
    <Section title="Rollouts of the Windows app">
      <QueryView
        query={rollouts}
        what="the rollouts"
        isEmpty={(list) => list.items.length === 0}
        empty={
          <Empty title="No release yet">
            A signed release starts here at 5% of hosts once the feed has it.
          </Empty>
        }
      >
        {(list) => (
          <Table
            caption="Rollouts"
            columns={[
              "Release",
              "Hosts offered",
              "Its hosts",
              "Sessions lost, 24 h",
              "Reconnects a day",
              "Why",
              "",
            ]}
          >
            {list.items.map((item) => (
              <Row key={item.id}>
                <Cell className="tabular font-semibold">{item.id}</Cell>
                <Cell>
                  {item.paused ? (
                    <StatusChip size="sm" domain="flag" state="paused" />
                  ) : (
                    <Tag>{item.percent}%</Tag>
                  )}
                  {item.signedPercent !== null &&
                    item.signedPercent !== undefined &&
                    item.signedPercent < item.percent && (
                      <span className="block text-caption text-text-2">signed for {item.signedPercent}%</span>
                    )}
                </Cell>
                <Cell className="tabular">
                  {item.hosts} <span className="text-text-2">of {item.hosts + item.restHosts}</span>
                </Cell>
                <Cell className="tabular">
                  {Math.round(item.failureRate * 100)}%{" "}
                  <span className="text-text-2">vs {Math.round(item.restFailureRate * 100)}%</span>
                </Cell>
                <Cell className="tabular">
                  {item.reconnectsPerHostDay}{" "}
                  <span className="text-text-2">vs {item.restReconnectsPerHostDay}</span>
                </Cell>
                <Cell className="text-small">
                  {item.reason}
                  <span className="block text-caption text-text-2">
                    <RelativeTime value={item.changedAt} />
                  </span>
                </Cell>
                <Cell>
                  <div className="flex flex-wrap gap-2">
                    {!item.paused && (
                      <ActionButton
                        permission="rollouts.pause"
                        variant="ghost"
                        onClick={() => setActing({ rollout: item, action: "pause" })}
                      >
                        Pause
                      </ActionButton>
                    )}
                    {(item.paused || item.percent < 100) && (
                      <ActionButton
                        permission="rollouts.widen"
                        onClick={() => {
                          setStep(String(STEPS.find((each) => Number(each) > item.percent) ?? item.percent));
                          setActing({ rollout: item, action: "widen" });
                        }}
                      >
                        {item.paused ? "Run again" : "Widen"}
                      </ActionButton>
                    )}
                  </div>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      <ActionSheet
        open={acting !== null}
        onOpenChange={(open) => !open && setActing(null)}
        record={rollout}
        destructive={acting?.action === "pause"}
        title={acting?.action === "pause" ? `Pause ${rollout?.id ?? ""}?` : `Widen ${rollout?.id ?? ""}?`}
        change={
          acting?.action === "pause"
            ? "No host is offered it from now. Hosts that already updated keep it; the agent's own checks never change."
            : "More hosts are offered it, chosen by host id, so everyone offered it now still is. Another admin approves; with no one else to, it runs at once."
        }
        extra={
          acting?.action === "widen" ? (
            <SmallSelect
              label="Share of hosts"
              value={step}
              onChange={setStep}
              options={STEPS.filter((each) => Number(each) >= (rollout?.percent ?? 0)).map((each) => [
                each,
                `${each}%`,
              ])}
            />
          ) : null
        }
        confirm={acting?.action === "pause" ? "Pause now" : "Ask to widen"}
        onConfirm={async (reason) => {
          const id = acting!.rollout.id;
          if (acting!.action === "pause") {
            await adminData(
              adminApi.POST("/admin/v1/rollouts/{id}/pause", { params: { path: { id } }, body: { reason } }),
            );
          } else {
            await adminData(
              adminApi.POST("/admin/v1/rollouts/{id}/widen", {
                params: { path: { id } },
                body: { reason, percent: Number(step) },
              }),
            );
          }
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}

function Summary() {
  const summary = useQuery({
    queryKey: [ADMIN, "fleet-summary"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/fleet/summary")),
  });
  return (
    <Section title="The fleet at a glance">
      <QueryView query={summary} what="the fleet">
        {(fleet) => (
          <div className="flex flex-col gap-4">
            <p className="text-body">
              <span className="tabular font-bold">{fleet.hosts}</span> hosts;{" "}
              {fleet.latestRelease ? (
                <>
                  <span className="tabular font-bold">{fleet.stragglers}</span> behind {fleet.latestRelease}
                  ;{" "}
                </>
              ) : null}
              <span className="tabular font-bold">{fleet.offlineDay}</span> not seen for more than a day;
              self-tests passed{" "}
              <span className="tabular font-bold">
                {fleet.selfTestPassRate == null ? "—" : `${Math.round(fleet.selfTestPassRate * 100)}%`}
              </span>{" "}
              over 30 days.
            </p>
            <div className="grid gap-4 *:min-w-0 md:grid-cols-2 xl:grid-cols-3">
              <Counts caption="Agent versions" counts={fleet.agentVersions} param="agentVersion" />
              <Counts caption="Distributions" counts={fleet.distroVersions} param="distroVersion" />
              <Counts caption="GPU models" counts={fleet.gpuModels} param="gpuModel" />
              <Counts caption="Drivers" counts={fleet.driverVersions} param="driverVersion" />
              <Counts caption="Blocklists" counts={fleet.blocklistVersions} param="blocklistVersion" />
            </div>
          </div>
        )}
      </QueryView>
    </Section>
  );
}

/** One kind's counts, each value a link to the hosts that have it. */
function Counts({ caption, counts, param }: { caption: string; counts: Count[]; param: string }) {
  return (
    <Table caption={caption} columns={["Value", "Hosts"]}>
      {counts.map((count) => (
        <Row key={count.value}>
          <Cell>
            <a
              href={`/admin/fleet/?${param}=${encodeURIComponent(count.value)}`}
              className="underline-offset-4 hover:underline"
            >
              {versionWords(count.value)}
            </a>
          </Cell>
          <Cell className="tabular">{count.hosts}</Cell>
        </Row>
      ))}
    </Table>
  );
}

type FleetFilter =
  "agentVersion" | "distroVersion" | "gpuModel" | "driverVersion" | "blocklistVersion" | "offline";

const FILTERS: Record<FleetFilter, string> = {
  agentVersion: "",
  distroVersion: "",
  gpuModel: "",
  driverVersion: "",
  blocklistVersion: "",
  offline: "",
};

function Hosts() {
  const client = useQueryClient();
  const [asked, setAsked] = useListParams({ ...FILTERS });
  const [draft, setDraft] = useState<Record<FleetFilter, string>>({ ...asked });
  const [notifying, setNotifying] = useState<string[] | null>(null);
  const [subject, setSubject] = useState("Please update the IdleO app");
  const view = useListView();
  const hosts = useInfiniteQuery({
    queryKey: [ADMIN, "fleet", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/fleet", {
          params: {
            query: {
              ...Object.fromEntries(Object.entries(asked).filter(([, value]) => value !== "")),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const owners = [
    ...new Set((hosts.data?.pages ?? []).flatMap((page) => page.hosts.map((host) => host.ownerId))),
  ];
  return (
    <Section title="Hosts">
      <ListBar
        list="fleet"
        filters={{
          agentVersion: "agentVersion",
          distroVersion: "distroVersion",
          gpuModel: "gpuModel",
          driverVersion: "driverVersion",
          blocklistVersion: "blocklistVersion",
          offline: "offline",
        }}
      />
      <FilterBar onSubmit={() => setAsked({ ...draft })}>
        <SmallField
          label="Agent version"
          value={draft.agentVersion}
          onChange={(value) => setDraft({ ...draft, agentVersion: value })}
          width="w-32"
        />
        <SmallField
          label="Distro"
          value={draft.distroVersion}
          onChange={(value) => setDraft({ ...draft, distroVersion: value })}
          width="w-32"
        />
        <SmallField
          label="GPU model"
          value={draft.gpuModel}
          onChange={(value) => setDraft({ ...draft, gpuModel: value })}
        />
        <SmallField
          label="Driver"
          value={draft.driverVersion}
          onChange={(value) => setDraft({ ...draft, driverVersion: value })}
          width="w-32"
        />
        <SmallField
          label="Blocklist"
          value={draft.blocklistVersion}
          onChange={(value) => setDraft({ ...draft, blocklistVersion: value })}
          width="w-56"
        />
        <SmallSelect
          label="Seen"
          value={draft.offline}
          onChange={(value) => setDraft({ ...draft, offline: value })}
          options={[
            ["", "Any time"],
            ["day", "Not for a day"],
          ]}
        />
      </FilterBar>
      <div className="flex justify-end">
        <ActionButton
          permission="notices.publish"
          variant="ghost"
          onClick={() => setNotifying(owners)}
          disabled={owners.length === 0}
          why="No laptops match, so there's no one to notify."
        >
          Notify these owners
        </ActionButton>
      </div>
      <PagedView
        query={hosts}
        what="hosts"
        items={(page) => page.hosts}
        empty={<Empty title="No hosts match">Clear a filter, or pick a value from the counts above.</Empty>}
      >
        {(rows) => (
          <Table
            caption="The fleet's hosts"
            list="fleet"
            sorts={{ Agent: "agentVersion", "Last seen": "lastSeenAt" }}
            columns={COLUMNS}
          >
            {rows.map((host) => (
              <Row key={host.id}>
                <Cell>
                  {host.name}
                  <span className="block">
                    <Id value={host.ownerId} page="user" label="owner" />
                  </span>
                </Cell>
                <Cell className="tabular">
                  {host.agentVersion ?? "—"}
                  {host.straggler && <span className="block text-caption text-danger">behind</span>}
                </Cell>
                <Cell className="tabular">{host.distroVersion ?? "—"}</Cell>
                <Cell>{host.gpuModel ?? "—"}</Cell>
                <Cell className="tabular">{host.driverVersion ?? "—"}</Cell>
                <Cell className="tabular">
                  {host.selfTestPassRate == null ? "—" : `${Math.round(host.selfTestPassRate * 100)}%`}
                </Cell>
                <Cell className="tabular">{host.reconnectsPerDay}</Cell>
                <Cell className="tabular text-small">{versionWords(host.blocklistVersion ?? "none")}</Cell>
                <Cell>{host.lastSeenAt ? <RelativeTime value={host.lastSeenAt} /> : "never"}</Cell>
                <Cell>
                  <RowLink href={`/admin/hosts/?id=${host.id}`}>
                    Open<span className="sr-only"> {host.name}</span>
                  </RowLink>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      <ActionSheet
        open={notifying !== null}
        onOpenChange={(open) => !open && setNotifying(null)}
        title={`Notify ${notifying?.length ?? 0} owners?`}
        change="They're emailed this notice from the hosts listed. More than 20 people goes to another admin first."
        extra={<SmallField label="Subject" value={subject} onChange={setSubject} width="w-72" />}
        confirm="Send the notice"
        onConfirm={async (reason) => {
          if (subject.trim() === "") {
            throw new SheetProblem("Give the notice a subject.");
          }
          await adminData(
            adminApi.POST("/admin/v1/notices", {
              body: {
                userIds: notifying!,
                subject: subject.trim(),
                body: "A newer version of the IdleO app is ready for your machine. Open the app and it updates when your machine is idle; nothing else is needed.",
                reason,
              },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}
