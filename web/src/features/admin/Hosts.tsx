import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Button, buttonClasses } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise } from "@/lib/money";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminHost, type AdminScore, type AdminSelfTestStatus } from "./api";
import {
  AdminPage,
  BackLink,
  Cell,
  Empty,
  Fields,
  Filters,
  Id,
  PagedView,
  Pill,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

/** Hosts (ADR 0027): find them; one opens with its history, GPUs, certificates and telemetry, and what can be done. */
export function Hosts() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <HostRecord id={id} /> : <HostSearch />;
}

const STATUSES: [string, string][] = [
  ["", "Any"],
  ["pending", "Pending"],
  ["verified", "Verified"],
  ["active", "Active"],
  ["suspended", "Suspended"],
  ["removed", "Removed"],
];

function HostSearch() {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [asked, setAsked] = useState({ query: "", status: "" });
  const hosts = useInfiniteQuery({
    queryKey: [ADMIN, "hosts", asked],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/hosts", {
          params: {
            query: {
              ...(asked.query ? { query: asked.query } : {}),
              ...(asked.status ? { status: asked.status } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Hosts">
      <Filters onSubmit={() => setAsked({ query: query.trim(), status })}>
        <SmallField label="Name, host id or owner id" value={query} onChange={setQuery} width="w-72" />
        <SmallSelect label="Status" value={status} onChange={setStatus} options={STATUSES} />
      </Filters>
      <PagedView
        query={hosts}
        what="hosts"
        items={(page) => page.hosts}
        empty={<Empty title="No hosts match">Try part of a name, or a whole host or owner id.</Empty>}
      >
        {(rows) => (
          <Table caption="Hosts" columns={["Name", "Status", "Agent", "GPUs", "Owner", "Last seen", ""]}>
            {rows.map((host) => (
              <Row key={host.id}>
                <Cell>{host.name}</Cell>
                <Cell>
                  <HostStatus status={host.status} />
                </Cell>
                <Cell>
                  {host.online ? (
                    <Pill tone="running">online</Pill>
                  ) : host.presence === "UNKNOWN" ? (
                    <Pill>unknown, checking…</Pill>
                  ) : (
                    <Pill>offline</Pill>
                  )}
                </Cell>
                <Cell>{host.gpuModels.join(", ") || "—"}</Cell>
                <Cell>
                  <Id value={host.ownerId} />
                </Cell>
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
    </AdminPage>
  );
}

/** The host score in numbers (the control plane's hosts module): what the scheduler ranks it by, and what it is made of. */
function HostScore({ score }: { score: AdminScore | null }) {
  return (
    <Section title="Host score">
      {score === null ? (
        <Empty title="No score yet">
          It is worked out each night and after each of its sessions ends. Until then it counts as average
          (0.5).
        </Empty>
      ) : (
        <Fields
          items={[
            ["Reliability", `${score.reliability.toFixed(3)} (${score.label})`],
            [
              "Bandwidth",
              `${score.bandwidth.toFixed(3)}${score.speedKbps ? `, ${Math.round(score.speedKbps / 1000)} Mbit/s` : ", not measured"}`,
            ],
            [
              "Sessions, 30 days",
              `${score.sessionsFinished} finished, ${score.sessionsLost} lost to the host`,
            ],
            ["Session score", score.sessionScore.toFixed(3)],
            ["Self-tests, 30 days", `${score.selfTestsPassed} passed, ${score.selfTestsFailed} failed`],
            ["Self-test score", score.selfTestScore.toFixed(3)],
            ["Upheld reports", String(score.reportsUpheld)],
            ["Admin flags", String(score.flags)],
            ["Taken off", score.penalty.toFixed(3)],
            ["Worked out", <RelativeTime key="at" value={score.computedAt} />],
          ]}
        />
      )}
    </Section>
  );
}

/** The newest self-test run from the web app, each check as it arrived. */
function SelfTestRun({ status }: { status: AdminSelfTestStatus | null }) {
  const run = status?.run;
  if (!run) {
    return null;
  }
  const words: Record<string, string> = {
    running: "running",
    passed: "passed",
    failed: "a check failed",
    refused: `the app wouldn't run it (${run.refused ?? "refused"})`,
    timed_out: "the app didn't answer in time",
  };
  return (
    <Section title="Self-test asked for here">
      <Table
        caption={`Asked for ${run.byAdmin ? "by an admin" : "by its owner"}: ${words[run.state] ?? run.state}`}
        columns={["Check", "Result", "What it found", "Took"]}
      >
        {run.checks.map((check) => (
          <Row key={check.name}>
            <Cell className="tabular">{check.name}</Cell>
            <Cell>{check.passed ? "passed" : "failed"}</Cell>
            <Cell>{check.detail}</Cell>
            <Cell className="tabular">{check.tookMs} ms</Cell>
          </Row>
        ))}
      </Table>
    </Section>
  );
}

function HostStatus({ status }: { status: string }) {
  return (
    <Pill
      tone={
        status === "active" ? "running" : status === "suspended" || status === "removed" ? "danger" : "plain"
      }
    >
      {status}
    </Pill>
  );
}

type Action = "activate" | "suspend" | "revoke-certificates" | "run-self-test";

/** How an update offered to the host ended, in words. */
const UPDATE_OUTCOMES: Record<string, string> = {
  installed: "installed",
  rolled_back: "rolled back",
  failed_verify: "didn't match the release",
  failed_download: "couldn't be downloaded",
};

const ACTIONS: Record<
  Action,
  { title: string; confirm: string; change: (host: AdminHost) => string; destructive: boolean }
> = {
  activate: {
    title: "Activate",
    confirm: "Activate",
    change: (host) =>
      `${host.name} goes from ${host.status} to active and can be offered sessions. Refused unless its agent has a valid certificate, a listing and a reported GPU.`,
    destructive: false,
  },
  suspend: {
    title: "Suspend",
    confirm: "Suspend",
    change: (host) =>
      `${host.name} goes from ${host.status} to suspended: no new sessions, and its agent is refused at its next connection.`,
    destructive: true,
  },
  "run-self-test": {
    title: "Run a self-test on",
    confirm: "Run self-test",
    change: (host) =>
      `${host.name}'s app runs its full self-test now and sends each check as it ends. It takes a minute or two; it can't run while a session does, and a machine gets 6 a day.`,
    destructive: false,
  },
  "revoke-certificates": {
    title: "Revoke the certificates of",
    confirm: "Revoke certificates",
    change: (host) =>
      `Every certificate ${host.name} still has (${host.certificates.filter((certificate) => certificate.revokedAt === null).length}) is revoked and its agent is cut off. Its owner must enrol it again with a new code.`,
    destructive: true,
  },
};

function HostRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const host = useQuery({
    queryKey: [ADMIN, "host", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/hosts/{id}", { params: { path: { id } } })),
    refetchInterval: 15_000,
  });
  const selfTest = useQuery({
    queryKey: [ADMIN, "host", id, "self-test"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/hosts/{id}/self-test", { params: { path: { id } } })),
    refetchInterval: (query) => (query.state.data?.run?.state === "running" ? 2_000 : false),
  });
  const [action, setAction] = useState<Action | null>(null);
  return (
    <AdminPage title="Host">
      <BackLink href="/admin/hosts/">← All hosts</BackLink>
      <QueryView query={host} what="this host">
        {(found) => (
          <>
            <Fields
              items={[
                ["Name", found.name],
                ["Status", <HostStatus key="status" status={found.status} />],
                [
                  "Agent",
                  found.online ? (
                    <span key="agent">
                      online
                      {found.connectedSince ? (
                        <RelativeTime value={found.connectedSince} prefix=", connected" />
                      ) : null}
                      {found.agentVersion ? `, version ${found.agentVersion}` : ""}
                    </span>
                  ) : (
                    `${found.presence === "UNKNOWN" ? "status unknown, checking…" : "offline"}${
                      found.agentVersion ? `, version ${found.agentVersion}` : ""
                    }`
                  ),
                ],
                [
                  "Host id",
                  <span key="id" className="tabular">
                    {found.id}
                  </span>,
                ],
                [
                  "Owner",
                  <Link
                    key="owner"
                    href={`/admin/users/?id=${found.ownerId}`}
                    className="tabular underline-offset-4 hover:underline"
                  >
                    {found.ownerId}
                  </Link>,
                ],
                [
                  "Price",
                  found.pricePaisePerMin == null
                    ? "no listing"
                    : `${formatPaise(found.pricePaisePerMin)} a minute (${formatPaise(found.pricePaisePerMin * 60)} an hour)`,
                ],
                ["Disk kept free", `${found.keepFreeGb} GB`],
                ["Agent", found.agentVersion ?? "hasn't connected yet"],
                [
                  "Last update",
                  found.lastUpdate
                    ? `${found.lastUpdate.version}: ${UPDATE_OUTCOMES[found.lastUpdate.outcome] ?? found.lastUpdate.outcome}${found.lastUpdate.detail ? ` (${found.lastUpdate.detail})` : ""}`
                    : "none offered yet",
                ],
                [
                  "Paused",
                  found.pausedAt ? (
                    found.pausedUntil ? (
                      <RelativeTime key="paused" value={found.pausedUntil} prefix="until" />
                    ) : (
                      "until its owner resumes"
                    )
                  ) : (
                    "no"
                  ),
                ],
                ["Added", <RelativeTime key="created" value={found.createdAt} />],
              ]}
            />
            <div className="flex flex-wrap gap-2">
              <Button
                variant="ghost"
                onClick={() => setAction("activate")}
                disabled={found.status === "active" || found.status === "removed"}
              >
                Activate
              </Button>
              <Button
                variant="ghost"
                onClick={() => setAction("suspend")}
                disabled={found.status === "suspended" || found.status === "removed"}
              >
                Suspend
              </Button>
              <Button
                variant="ghost"
                onClick={() => setAction("revoke-certificates")}
                disabled={found.certificates.every((certificate) => certificate.revokedAt !== null)}
              >
                Revoke certificates
              </Button>
              <Button
                variant="ghost"
                onClick={() => setAction("run-self-test")}
                disabled={!selfTest.data?.canRun}
              >
                Run self-test
              </Button>
              <Link href={`/admin/sessions/?host=${found.id}`} className={buttonClasses("ghost")}>
                Sessions it ran
              </Link>
            </div>
            {selfTest.data && !selfTest.data.canRun && selfTest.data.whyNot ? (
              <p className="text-small text-text-2">{selfTest.data.whyNot.message}</p>
            ) : null}
            <HostScore score={found.score ?? null} />
            <SelfTestRun status={selfTest.data ?? null} />
            <Section title="Self-test">
              {found.selfTest ? (
                <Table
                  caption={`Self-test ${found.selfTest.passed ? "passed" : "failed"}, agent ${found.selfTest.agentVersion ?? "—"}, distro ${found.selfTest.distroVersion || "—"}`}
                  columns={["Check", "Result", "What it found", "Took"]}
                >
                  {found.selfTest.checks.map((check) => (
                    <Row key={check.name}>
                      <Cell className="tabular">{check.name}</Cell>
                      <Cell>{check.passed ? "passed" : "failed"}</Cell>
                      <Cell>{check.detail}</Cell>
                      <Cell className="tabular">{check.tookMs} ms</Cell>
                    </Row>
                  ))}
                </Table>
              ) : (
                <Empty title="No self-test yet">
                  Its agent runs one after it links, and after each update.
                </Empty>
              )}
            </Section>
            {action !== null && (
              <ActionSheet
                open
                onOpenChange={(next) => !next && setAction(null)}
                title={`${ACTIONS[action].title} ${found.name}?`}
                change={ACTIONS[action].change(found)}
                confirm={ACTIONS[action].confirm}
                destructive={ACTIONS[action].destructive}
                onConfirm={async (reason) => {
                  const path =
                    action === "activate"
                      ? "/admin/v1/hosts/{id}/activate"
                      : action === "suspend"
                        ? "/admin/v1/hosts/{id}/suspend"
                        : action === "run-self-test"
                          ? "/admin/v1/hosts/{id}/self-tests"
                          : "/admin/v1/hosts/{id}/revoke-certificates";
                  await adminData(adminApi.POST(path, { params: { path: { id } }, body: { reason } }));
                  await client.invalidateQueries({ queryKey: [ADMIN] });
                }}
              />
            )}
            <Section title="Telemetry">
              {found.telemetry.now ? (
                <Table caption="GPUs now" columns={["GPU", "Temperature", "Use", "Memory used"]}>
                  {found.telemetry.now.gpus.map((gpu) => (
                    <Row key={gpu.gpuUuid}>
                      <Cell className="tabular">{gpu.gpuUuid}</Cell>
                      <Cell className="tabular">{gpu.tempC} °C</Cell>
                      <Cell className="tabular">{gpu.utilPct}%</Cell>
                      <Cell className="tabular">{gpu.vramUsedMb} MB</Cell>
                    </Row>
                  ))}
                </Table>
              ) : (
                <Empty title="No reading now">
                  Its agent isn&apos;t connected, so there&apos;s no heartbeat to show.
                </Empty>
              )}
            </Section>
            <Section title="GPUs">
              {found.gpus.length === 0 ? (
                <Empty title="No GPU reported">Its agent reports its GPUs when it first connects.</Empty>
              ) : (
                <Table caption="GPUs" columns={["Model", "Memory", "Driver", "First seen", "Gone"]}>
                  {found.gpus.map((gpu) => (
                    <Row key={gpu.uuid}>
                      <Cell>{gpu.model}</Cell>
                      <Cell className="tabular">{Math.round(gpu.vramMb / 1024)} GB</Cell>
                      <Cell>{gpu.driverVersion ?? "—"}</Cell>
                      <Cell>
                        <RelativeTime value={gpu.firstSeenAt} />
                      </Cell>
                      <Cell>{gpu.goneAt ? <RelativeTime value={gpu.goneAt} /> : "—"}</Cell>
                    </Row>
                  ))}
                </Table>
              )}
            </Section>
            <Section title="Certificates">
              {found.certificates.length === 0 ? (
                <Empty title="No certificates">It gets one when its agent enrols with a code.</Empty>
              ) : (
                <Table caption="Certificates" columns={["Serial", "Issued", "Expires", "Revoked"]}>
                  {found.certificates.map((certificate) => (
                    <Row key={certificate.serial}>
                      <Cell className="tabular">{certificate.serial.slice(-12)}</Cell>
                      <Cell>
                        <RelativeTime value={certificate.createdAt} />
                      </Cell>
                      <Cell>
                        <RelativeTime value={certificate.notAfter} />
                      </Cell>
                      <Cell>
                        {certificate.revokedAt ? <RelativeTime value={certificate.revokedAt} /> : "—"}
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )}
            </Section>
            <Section title="Status history">
              {found.statusHistory.length === 0 ? (
                <Empty title="No changes yet">
                  Every activation, suspension and removal shows here, with who and why.
                </Empty>
              ) : (
                <Table caption="Status history" columns={["Change", "Why", "By", "When"]}>
                  {found.statusHistory.map((change) => (
                    <Row key={`${change.at}-${change.to}`}>
                      <Cell>
                        {change.from} → {change.to}
                      </Cell>
                      <Cell>{change.reason}</Cell>
                      <Cell>{change.changedBy}</Cell>
                      <Cell>
                        <RelativeTime value={change.at} />
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )}
            </Section>
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}
