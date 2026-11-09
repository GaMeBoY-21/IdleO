import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link } from "wouter";
import { readEvents } from "@/api/stream";
import { config } from "@/config";
import { useNow } from "@/lib/clock";
import { cn } from "@/lib/cn";
import { formatPaise } from "@/lib/money";
import { Skeleton } from "@/components/ui/Skeleton";
import { RelativeTime } from "@/components/ui/Time";
import { useCan } from "./permissions";
import { ADMIN, adminApi, adminData, type AdminInstalls, type Schemas } from "./api";
import { word } from "./words";
import { AdminPage, Cell, Empty, Pill, QueryView, Row, Section, SmallSelect, Table } from "./ui";
import { ListBar } from "./Lists";
import { RecordTabs } from "./RecordTabs";
import { ListScope, useListParam, useListQuery, useListView } from "./listView";

const INSTALL_COLUMNS = ["Opened", "Minutes", "Clicks", "Where it got to", "GPU", "Windows", "Why it failed"];

type ControlRoom = Schemas["ControlRoomDashboard"];
type HeatCell = Schemas["ControlRoomCell"];
type DemandCell = Schemas["ControlRoomDemand"];
type CohortStep = Schemas["ControlRoomCohortStep"];

/** Each part's age past which the page says it's stale: two missed rollups. */
const STALE_MS = { health: 30_000, other: 150_000 };

/**
 * The control room (ADR 0056): the marketplace, supply and demand, the funnels, the control plane's own health and, for
 * an admin who can read money, money; all from the rollups, sent every 10 seconds over the dashboard's stream. It asks
 * again itself only while that stream is down. Every figure opens the list behind it.
 */
export function Dashboard() {
  const can = useCan();
  const client = useQueryClient();
  const [streamDown, setStreamDown] = useState(false);
  const room = useQuery({
    queryKey: [ADMIN, "control-room"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/control-room")),
    // The stream keeps it fresh; only after it's been lost does this.
    refetchInterval: streamDown ? 10_000 : false,
    enabled: can("ops.dashboard.read"),
  });
  useEffect(() => {
    if (!can("ops.dashboard.read")) {
      return;
    }
    const stop = new AbortController();
    void (async () => {
      while (!stop.signal.aborted) {
        try {
          const { response } = await adminApi.GET("/admin/v1/control-room/stream", {
            parseAs: "stream",
            signal: stop.signal,
          });
          if (!response.ok) {
            setStreamDown(true);
            break;
          }
          await readEvents(response, (event) => {
            if (event.event === "dashboard") {
              setStreamDown(false);
              client.setQueryData([ADMIN, "control-room"], JSON.parse(event.data) as ControlRoom);
            }
          });
        } catch {
          // Closed, or the network blinked: polling covers it until the stream is back.
          setStreamDown(true);
        }
        await new Promise((resolve) => setTimeout(resolve, 1_000));
      }
    })();
    return () => stop.abort();
  }, [can, client]);
  return (
    <AdminPage title="Control room">
      {can("ops.dashboard.read") && (
        <QueryView
          query={room}
          what="the control room"
          skeleton={
            <div role="status" aria-label="Loading the control room" className="grid gap-3 lg:grid-cols-2">
              {["money", "marketplace", "supply", "funnels"].map((part) => (
                <Skeleton key={part} className="h-56 w-full" />
              ))}
            </div>
          }
        >
          {(figures) => <Panels figures={figures} />}
        </QueryView>
      )}
      {can("hosts.read") && (
        <ListScope.Provider value="installs">
          <Installs />
        </ListScope.Provider>
      )}
    </AdminPage>
  );
}

function Panels({ figures }: { figures: ControlRoom }) {
  const now = useNow("half-minute");
  const market = figures.marketplace;
  const supply = figures.supply;
  const stale = (part: string) => {
    const at = figures.computedAt[part];
    return at === undefined || now - Date.parse(at) > (part === "health" ? STALE_MS.health : STALE_MS.other);
  };
  return (
    <div className="flex flex-col gap-6">
      <div className="grid gap-6 *:min-w-0 xl:grid-cols-2">
        {figures.money && (
          <Panel title="Money" at={figures.computedAt.money} stale={stale("money")}>
            <Table caption="Money, tied to the ledger" columns={["", "Today", "7 days", "30 days"]}>
              {(
                [
                  ["GMV", "gmvPaise", "/admin/money-reports/"],
                  ["Take", "takePaise", "/admin/money-reports/"],
                  ["Refunds", "refundsPaise", "/admin/refunds/"],
                  ["Chargebacks", "chargebacksPaise", "/admin/chargebacks/"],
                ] as const
              ).map(([label, key, href]) => (
                <Row key={key}>
                  <Cell>
                    <Link href={href} className="font-medium underline-offset-4 hover:underline">
                      {label}
                    </Link>
                  </Cell>
                  {(["today", "week", "month"] as const).map((window) => (
                    <Cell key={window} className="tabular">
                      {formatPaise(figures.money![window][key])}
                    </Cell>
                  ))}
                </Row>
              ))}
            </Table>
          </Panel>
        )}
        {market && (
          <Panel title="Marketplace" at={figures.computedAt.marketplace} stale={stale("marketplace")}>
            <ul aria-label="The marketplace now" className="grid grid-cols-2 gap-3 md:grid-cols-3">
              <Figure
                label="Sessions running"
                value={market.sessionsRunning}
                href="/admin/sessions/?state=RUNNING"
              />
              <Figure
                label="Waiting to match"
                value={market.bookingsWaiting}
                href="/admin/sessions/?state=REQUESTED"
              />
              <Figure label="Booked today" value={market.bookingsToday} href="/admin/sessions/" />
              <Figure
                label="Time to match"
                value={market.matchP50Seconds == null ? "—" : `${market.matchP50Seconds} s`}
                means={
                  market.matchP95Seconds == null ? "none matched today" : `p95 ${market.matchP95Seconds} s`
                }
                href="/admin/sessions/"
              />
              <Figure
                label="Starts that ran"
                value={
                  market.startsAttempted === 0
                    ? "—"
                    : `${Math.round((100 * market.startsSucceeded) / market.startsAttempted)}%`
                }
                means={`${market.startsSucceeded} of ${market.startsAttempted} in 24 h`}
                href="/admin/sessions/"
              />
            </ul>
            {market.failures.length > 0 && (
              <Table caption="Why sessions failed, last 24 hours" columns={["Reason", "How many"]}>
                {market.failures.map((failure) => (
                  <Row key={failure.reason}>
                    <Cell>
                      <Link
                        href="/admin/sessions/?state=FAILED"
                        className="underline-offset-4 hover:underline"
                      >
                        {word(failure.reason)}
                      </Link>
                    </Cell>
                    <Cell className="tabular">{failure.count}</Cell>
                  </Row>
                ))}
              </Table>
            )}
          </Panel>
        )}
      </div>
      {supply && (
        <Panel title="Supply and demand" at={figures.computedAt.supply} stale={stale("supply")}>
          <Table
            caption="Hosts by GPU model"
            columns={["GPU model", "Online now", "Online GPU-hours, 24 h", "Utilisation"]}
          >
            {models(supply).map((model) => (
              <Row key={model}>
                <Cell>
                  <Link
                    href={`/admin/hosts/?query=${encodeURIComponent(model)}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {model}
                  </Link>
                </Cell>
                <Cell className="tabular">
                  {supply.online == null
                    ? "Unknown"
                    : (supply.online.find((row) => row.model === model)?.value ?? 0)}
                </Cell>
                <Cell className="tabular">
                  {supply.onlineGpuHours.find((row) => row.model === model)?.value ?? 0}
                </Cell>
                <Cell className="tabular">
                  {(() => {
                    const ratio = figures.utilisation.find((row) => row.model === model)?.value;
                    return ratio === undefined ? "—" : `${Math.round(ratio * 100)}%`;
                  })()}
                </Cell>
              </Row>
            ))}
          </Table>
          <div className="grid gap-6 *:min-w-0 xl:grid-cols-2">
            <Heatmap
              caption="Demand: session-hours by hour of day, India, last 7 days"
              cells={market?.demand ?? []}
            />
            <Heatmap
              caption="Supply: hosts online by hour of day, India, last 7 days"
              cells={supply.supply}
            />
          </div>
        </Panel>
      )}
      <Panel title="Demand asked, served and missed" at={figures.computedAt.demand} stale={stale("demand")}>
        <Demand cells={figures.demand} />
      </Panel>
      <div className="grid gap-6 *:min-w-0 xl:grid-cols-2">
        <Panel title="Funnels" at={figures.computedAt.funnels} stale={stale("funnels")}>
          <RecordTabs
            label="How the funnels are counted"
            tabs={[
              {
                id: "cohorts",
                label: "By sign-up week",
                needs: "",
                render: () => <Cohorts rows={figures.cohorts} />,
              },
              {
                id: "all-time",
                label: "All time",
                needs: "",
                render: () => <AllTime steps={figures.funnels} />,
              },
            ]}
          />
        </Panel>
        <Panel title="Health" at={figures.computedAt.health} stale={stale("health")}>
          <ul aria-label="The control plane's checks" className="flex flex-col gap-2">
            {figures.health.map((check) => (
              <li key={check.name} className="flex items-start gap-3 text-small">
                <Pill tone={check.ok ? "running" : "danger"}>{check.ok ? "ok" : "look"}</Pill>
                <span>
                  {HEALTH_LINKS[check.name] !== undefined ? (
                    <Link
                      href={HEALTH_LINKS[check.name] ?? "/admin/"}
                      className="inline-flex min-h-6 items-center font-medium underline-offset-4 hover:underline"
                    >
                      {check.name}
                    </Link>
                  ) : (
                    <span className="font-medium">{check.name}</span>
                  )}
                  <span className="block text-caption text-text-2">{check.detail}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="text-caption text-text-2">
            {config.grafanaUrl ? (
              <a
                href={`${config.grafanaUrl}/alerting/list`}
                className="inline-flex min-h-6 items-center underline underline-offset-4"
              >
                Alerts in Grafana
              </a>
            ) : (
              "Alerts are in Grafana Cloud (docs/runbooks/monitoring.md)."
            )}
          </p>
        </Panel>
      </div>
    </div>
  );
}

/** The funnels over everyone there's ever been: each step's people, and their share of the step before. */
function AllTime({ steps }: { steps: Schemas["ControlRoomStep"][] }) {
  return (
    <div className="flex flex-col gap-4">
      {(["renters", "hosts"] as const).map((funnel) => (
        <Table
          key={funnel}
          caption={funnel === "renters" ? "Renters, all time" : "Hosts, all time"}
          columns={["Step", "People", "Of the step before"]}
        >
          {steps
            .filter((step) => step.funnel === funnel)
            .map((step, index, all) => (
              <Row key={step.step}>
                <Cell>
                  <Link
                    href={funnel === "renters" ? "/admin/users/" : "/admin/hosts/"}
                    className="underline-offset-4 hover:underline"
                  >
                    {step.step}
                  </Link>
                </Cell>
                <Cell className="tabular">{step.people}</Cell>
                <Cell className="tabular">
                  {share(step.people, index === 0 ? undefined : all[index - 1]?.people)}
                </Cell>
              </Row>
            ))}
        </Table>
      ))}
    </div>
  );
}

/** One step's figure for a week: its share of everyone who signed up then, and whether that can still grow. */
function within(count: number, signedUp: number, whole: boolean): string {
  const shown = signedUp === 0 ? "—" : `${Math.round((100 * count) / signedUp)}% (${count})`;
  return whole ? shown : `${shown}, so far`;
}

/**
 * The funnels by sign-up week: everyone who joined that week, and how many of them reached each step within 7 and 30
 * days of joining. A week still inside its window says "so far", since its figure can still grow.
 */
function Cohorts({ rows }: { rows: CohortStep[] }) {
  if (rows.length === 0) {
    return (
      <Empty title="No sign-ups in the last 12 weeks">
        Each week's cohort shows here once someone joins.
      </Empty>
    );
  }
  return (
    <div className="flex flex-col gap-4">
      {(["renters", "hosts"] as const).map((funnel) => {
        const mine = rows.filter((row) => row.funnel === funnel);
        const steps = [...new Map(mine.map((row) => [row.order, row.step])).entries()].sort(
          ([one], [other]) => one - other,
        );
        const weeks = [...new Set(mine.map((row) => row.week))].sort().reverse();
        return (
          <Table
            key={funnel}
            caption={funnel === "renters" ? "Renters by sign-up week" : "Hosts by sign-up week"}
            columns={[
              "Week of",
              "Signed up",
              ...steps.flatMap(([, step]) => [`${step}, 7 days`, `${step}, 30 days`]),
            ]}
          >
            {weeks.map((week) => {
              const inWeek = mine.filter((row) => row.week === week);
              return (
                <Row key={week}>
                  <Cell className="tabular whitespace-nowrap">{week}</Cell>
                  <Cell className="tabular">{inWeek[0]?.signedUp ?? 0}</Cell>
                  {steps.flatMap(([order]) => {
                    const row = inWeek.find((each) => each.order === order);
                    return [
                      <Cell key={`${order}-7`} className="tabular whitespace-nowrap">
                        {row ? within(row.within7, row.signedUp, row.whole7) : "—"}
                      </Cell>,
                      <Cell key={`${order}-30`} className="tabular whitespace-nowrap">
                        {row ? within(row.within30, row.signedUp, row.whole30) : "—"}
                      </Cell>,
                    ];
                  })}
                </Row>
              );
            })}
          </Table>
        );
      })}
    </div>
  );
}

const DEMAND_WORDS = { gap: "Missed: no host", asked: "Asked", served: "Served" } as const;
type DemandMeasure = keyof typeof DEMAND_WORDS;

/** The sessions list behind a cell: the bookings of that class and hour over the same 7 days. */
function behind(cell: DemandCell, measure: DemandMeasure, since: string): string {
  const query = new URLSearchParams({
    demandClass: String(cell.classMb),
    demandHour: String(cell.hour),
    since,
  });
  if (measure === "gap") {
    query.set("state", "NO_HOST_FREE");
  }
  if (measure === "served") {
    query.set("served", "true");
  }
  return `/admin/sessions/?${query.toString()}`;
}

/**
 * What was asked for, what ran and what ended without a host, by GPU class and hour in India, over the last 7 days. A
 * booking names the memory it needs and not a model, so a class is what was asked for. The table under the heatmap holds
 * every figure again, and each opens the bookings behind it.
 */
function Demand({ cells }: { cells: DemandCell[] }) {
  const [measure, setMeasure] = useListParam("demand", "gap");
  const shown = (measure in DEMAND_WORDS ? measure : "gap") as DemandMeasure;
  const now = useNow("half-minute");
  const since = new Date(now - 7 * 24 * 3_600_000).toISOString();
  if (cells.length === 0) {
    return (
      <Empty title="No bookings in the last 7 days">
        What was asked for shows here by GPU class and hour.
      </Empty>
    );
  }
  const worstFirst = [...cells].sort(
    (one, other) => other.gap - one.gap || other.asked - one.asked || one.hour - other.hour,
  );
  return (
    <div className="flex flex-col gap-4">
      <SmallSelect label="Show" value={shown} onChange={setMeasure} options={Object.entries(DEMAND_WORDS)} />
      <Heatmap
        caption={`${DEMAND_WORDS[shown]}: bookings by GPU class and hour of day, India, last 7 days`}
        cells={cells.map((cell) => ({ model: cell.gpu, hour: cell.hour, value: cell[shown] }))}
        href={(gpu, hour) => {
          const cell = cells.find((each) => each.gpu === gpu && each.hour === hour);
          return cell ? behind(cell, shown, since) : null;
        }}
      />
      <Table
        caption="Every hour with a booking, the most missed first"
        columns={["GPU class", "Hour, India", "Asked", "Served", "Missed"]}
      >
        {worstFirst.map((cell) => (
          <Row key={`${cell.classMb}-${cell.hour}`}>
            <Cell>{cell.gpu}</Cell>
            <Cell className="tabular">{`${String(cell.hour).padStart(2, "0")}:00`}</Cell>
            {(["asked", "served", "gap"] as const).map((each) => (
              <Cell key={each} className="tabular">
                {cell[each] === 0 ? (
                  0
                ) : (
                  <Link href={behind(cell, each, since)} className="underline underline-offset-4">
                    {cell[each]}
                    <span className="sr-only">
                      {" "}
                      {DEMAND_WORDS[each]}, {cell.gpu} at {cell.hour}:00: open these bookings
                    </span>
                  </Link>
                )}
              </Cell>
            ))}
          </Row>
        ))}
      </Table>
    </div>
  );
}

/** Where each check's list is, when it has one. */
const HEALTH_LINKS: Record<string, string> = {
  "settlement reconciliation": "/admin/settlements/",
  "wallet reconciliation": "/admin/money/",
  "inbox targets": "/admin/inbox/",
  "review queue": "/admin/review/",
  scanner: "/admin/images/",
  "agent streams": "/admin/hosts/",
};

/** A step's people as a share of the step before's, or a dash for the first step or one after nobody. */
function share(people: number, before: number | undefined): string {
  return before === undefined || before === 0 ? "—" : `${Math.round((100 * people) / before)}%`;
}

/** Every model the supply knows of, in order. */
function models(supply: Schemas["ControlRoomSupply"]): string[] {
  const names = new Set<string>();
  supply.online?.forEach((row) => names.add(row.model));
  supply.onlineGpuHours.forEach((row) => names.add(row.model));
  return [...names].sort();
}

function Panel({
  title,
  at,
  stale,
  children,
}: {
  title: string;
  at: string | undefined;
  stale: boolean;
  children: React.ReactNode;
}) {
  return (
    <Section title={title}>
      <div className="flex flex-col gap-4">
        {children}
        <p className={stale ? "text-caption text-danger" : "text-caption text-text-2"}>
          {at ? (
            <RelativeTime value={at} prefix={stale ? "Stale: worked out" : "Worked out"} />
          ) : (
            "Not worked out yet"
          )}
        </p>
      </div>
    </Section>
  );
}

function Figure({
  label,
  value,
  means,
  href,
}: {
  label: string;
  value: number | string;
  means?: string;
  href: string;
}) {
  return (
    <li className="flex h-24 flex-col justify-between rounded-inner border border-edge bg-panel p-3">
      <Link href={href} className="min-h-6 text-small font-medium underline-offset-4 hover:underline">
        {label}
      </Link>
      <span className="tabular font-title text-title">{value}</span>
      <span className="text-caption text-text-2">{means ?? "\u00a0"}</span>
    </li>
  );
}

const SHADES = ["", "bg-progress/10", "bg-progress/15", "bg-progress/20", "bg-progress/30"];

/** A GPU model by hour of day in India, each cell shaded by its share of the busiest, with its value for a screen reader. */
function Heatmap({
  caption,
  cells,
  href,
}: {
  caption: string;
  cells: HeatCell[];
  /** The list behind a cell, when it has one: its number then opens it. */
  href?: (model: string, hour: number) => string | null;
}) {
  const rows = [...new Set(cells.map((cell) => cell.model))].sort();
  const most = Math.max(0, ...cells.map((cell) => cell.value));
  if (rows.length === 0) {
    return <Empty title={caption}>Nothing in the last 7 days yet.</Empty>;
  }
  return (
    <div className="min-w-0 overflow-x-auto">
      <table className="w-full border-collapse text-caption">
        <caption className="pb-2 text-left text-small font-semibold">{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className="pr-2 text-left font-medium">
              GPU model
            </th>
            {Array.from({ length: 24 }, (_, hour) => (
              <th key={hour} scope="col" className="w-6 text-center font-normal text-text-2">
                {hour % 6 === 0 ? hour : <span className="sr-only">{hour}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((model) => (
            <tr key={model}>
              <th scope="row" className="pr-2 text-left font-medium whitespace-nowrap">
                {model}
              </th>
              {Array.from({ length: 24 }, (_, hour) => {
                const value = cells.find((cell) => cell.model === model && cell.hour === hour)?.value ?? 0;
                const shade = most === 0 || value === 0 ? 0 : Math.max(1, Math.ceil((4 * value) / most));
                return (
                  <td key={hour} className={cn("h-6 border border-line p-0", SHADES[shade])}>
                    {value > 0 && href?.(model, hour) ? (
                      <Link
                        href={href(model, hour) ?? ""}
                        className="flex h-6 w-full items-center justify-center text-caption tabular"
                        title={`${model} at ${hour}:00: ${value}`}
                      >
                        {value}
                        <span className="sr-only">
                          {" "}
                          at {hour}:00, {model}: open these bookings
                        </span>
                      </Link>
                    ) : (
                      value > 0 && (
                        <span className="flex h-6 items-center justify-center text-caption tabular">
                          {Math.round(value * 10) / 10}
                          <span className="sr-only"> at {hour}:00</span>
                        </span>
                      )
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Minutes with one decimal, or a dash. */
const minutes = (value: number | null | undefined) => (value == null ? "—" : `${value.toFixed(1)} min`);

/**
 * The Installs card: how long installs take from opening the setup app to the laptop
 * listed, the last 20 with their clicks and where each stopped, and the failures by reason, over the last 30 days.
 */
function Installs() {
  const view = useListView();
  const installs = useListQuery(
    [ADMIN, "installs", view],
    "recent",
    (cursor?: string) =>
      adminData(
        adminApi.GET("/admin/v1/installs", { params: { query: { ...view, ...(cursor ? { cursor } : {}) } } }),
      ),
    { refetchInterval: 60_000 },
  );
  return (
    <Section title="Installs">
      <ListBar list="installs" tags={false} />
      <QueryView
        query={installs}
        what="the installs"
        skeleton={<Skeleton className="h-40 w-full" />}
        isEmpty={(data: AdminInstalls) => data.opened === 0}
        empty={
          <Empty title="No installs yet">The setup app's measurements show here once someone opens it.</Empty>
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            <p className="text-body">
              <span className="tabular font-bold">{minutes(data.medianMinutes)}</span> median from opening
              setup to listed, over {data.listed} of {data.opened} installs in the last 30 days.
            </p>
            <Table
              caption="Recent installs"
              list="installs"
              sorts={{ Opened: "openedAt", Clicks: "clicks" }}
              columns={INSTALL_COLUMNS}
            >
              {data.recent.map((install) => (
                <InstallRow key={install.installId} install={install} />
              ))}
            </Table>
            {data.failures.length > 0 && (
              <Table caption="Failures by reason" columns={["Step", "Reason", "How many"]}>
                {data.failures.map((failure) => (
                  <Row key={`${failure.step}-${failure.reason}`}>
                    <Cell>{failure.step}</Cell>
                    <Cell>{word(failure.reason)}</Cell>
                    <Cell className="tabular">{failure.count}</Cell>
                  </Row>
                ))}
              </Table>
            )}
          </div>
        )}
      </QueryView>
    </Section>
  );
}

type Install = AdminInstalls["recent"][number];

/**
 * One install, and when it has words behind a failure (the error's text or a self-test check's finding, which the setup
 * app scrubbed of the person and the machine before sending), a button that opens them in a row under it.
 */
function InstallRow({ install }: { install: Install }) {
  const [open, setOpen] = useState(false);
  const count = install.details.length;
  const panel = `install-${install.installId}`;
  return (
    <>
      <Row>
        <Cell>
          <RelativeTime value={install.openedAt} />
        </Cell>
        <Cell className="tabular">{minutes(install.minutes)}</Cell>
        <Cell className="tabular">{install.clicks}</Cell>
        <Cell>{word(install.stoppedAt)}</Cell>
        <Cell>{install.gpuModel ?? "—"}</Cell>
        <Cell className="tabular">{install.windowsBuild ?? "—"}</Cell>
        <Cell>
          {count === 0 ? (
            "—"
          ) : (
            <button
              type="button"
              aria-expanded={open}
              aria-controls={panel}
              onClick={() => setOpen((was) => !was)}
              className="inline-flex min-h-touch items-center font-semibold underline-offset-4 hover:underline"
            >
              {count === 1 ? "1 reason" : `${count} reasons`}
            </button>
          )}
        </Cell>
      </Row>
      {open && (
        <tr id={panel} className="border-b border-line bg-panel-2">
          <td colSpan={7} className="px-2 py-3">
            <ul className="flex flex-col gap-3">
              {install.details.map((each) => (
                <li key={`${each.step}-${each.reason ?? ""}-${each.at}`} className="flex flex-col gap-1">
                  <p className="text-caption text-text-2">
                    {each.step} · {each.reason ? word(each.reason) : "no reason code"} ·{" "}
                    <RelativeTime value={each.at} />
                  </p>
                  <p className="whitespace-pre-wrap break-words text-small">{each.detail}</p>
                </li>
              ))}
            </ul>
          </td>
        </tr>
      )}
    </>
  );
}
