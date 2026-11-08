import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useSearch } from "wouter";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise } from "@/lib/money";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { word } from "./words";
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
  Timeline,
} from "./ui";
import { usePaletteActions } from "./Palette";
import { SessionTabs } from "./Records";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { useListParams, useListView } from "./listView";

/** Sessions (ADR 0027): find them; one opens with its timeline and attempts, and can be stopped while it runs. */
export function Sessions() {
  const search = new URLSearchParams(useSearch());
  const id = search.get("id");
  return id ? (
    <SessionRecord id={id} />
  ) : (
    <SessionSearch user={search.get("user") ?? ""} host={search.get("host") ?? ""} />
  );
}

const STATES = [
  "REQUESTED",
  "MATCHED",
  "PROVISIONING",
  "RUNNING",
  "HOST_LOST",
  "FINISHING",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "NO_HOST_FREE",
  "ENDED_REFUNDED",
];

/** A date typed as YYYY-MM-DD, as midnight in India; empty when not given or not a date. */
function indiaMidnight(date: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(date) ? new Date(`${date}T00:00:00+05:30`).toISOString() : "";
}

function SessionSearch({ user, host }: { user: string; host: string }) {
  const [asked, setAsked] = useListParams({
    session: "",
    user,
    host,
    state: "",
    from: "",
    to: "",
    // From a cell of the control room's demand heatmap.
    demandClass: "",
    demandHour: "",
    served: "",
    since: "",
  });
  const [form, setForm] = useState(asked);
  const view = useListView();
  const set = (field: keyof typeof form) => (value: string) => setForm({ ...form, [field]: value });
  const sessions = useInfiniteQuery({
    queryKey: [ADMIN, "sessions", asked, view],
    queryFn: ({ pageParam }) => {
      const from = asked.since || indiaMidnight(asked.from);
      const to = indiaMidnight(asked.to);
      return adminData(
        adminApi.GET("/admin/v1/sessions", {
          params: {
            query: {
              ...(asked.session ? { id: asked.session } : {}),
              ...(asked.user ? { user: asked.user } : {}),
              ...(asked.host ? { host: asked.host } : {}),
              ...(asked.state ? { state: asked.state } : {}),
              ...(from ? { from } : {}),
              ...(to ? { to } : {}),
              ...(asked.demandClass ? { demandClass: Number(asked.demandClass) } : {}),
              ...(asked.demandHour ? { demandHour: Number(asked.demandHour) } : {}),
              ...(asked.served ? { served: asked.served === "true" } : {}),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      );
    },
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Sessions">
      <ListBar
        list="sessions"
        filters={{ session: "id", user: "user", host: "host" }}
        extra={{
          ...(asked.state ? { state: asked.state.toUpperCase() } : {}),
          ...(asked.since || indiaMidnight(asked.from)
            ? { from: asked.since || indiaMidnight(asked.from) }
            : {}),
          ...(indiaMidnight(asked.to) ? { to: indiaMidnight(asked.to) } : {}),
          ...(asked.demandClass ? { demandClass: asked.demandClass } : {}),
          ...(asked.demandHour ? { demandHour: asked.demandHour } : {}),
          ...(asked.served ? { served: asked.served } : {}),
        }}
      />
      {asked.demandClass && (
        <p role="status" className="flex flex-wrap items-center gap-3 text-small">
          Bookings for a {Math.round(Number(asked.demandClass) / 1_000)} GB GPU asked at {asked.demandHour}:00
          in India, over the last 7 days.
          <Link
            href="/admin/sessions/"
            className="inline-flex min-h-touch items-center underline underline-offset-4 md:min-h-9"
          >
            Show every session
          </Link>
        </p>
      )}
      <Filters
        onSubmit={() =>
          setAsked({ ...form, session: form.session.trim(), user: form.user.trim(), host: form.host.trim() })
        }
      >
        <SmallField label="Session id" value={form.session} onChange={set("session")} />
        <SmallField label="Renter id" value={form.user} onChange={set("user")} />
        <SmallField label="Host id" value={form.host} onChange={set("host")} />
        <SmallSelect
          label="State"
          value={form.state}
          onChange={set("state")}
          options={[["", "Any"], ...STATES.map((state): [string, string] => [state, word(state)])]}
        />
        <SmallField label="Booked from" type="date" value={form.from} onChange={set("from")} width="w-40" />
        <SmallField label="Booked before" type="date" value={form.to} onChange={set("to")} width="w-40" />
      </Filters>
      <PagedView
        query={sessions}
        what="sessions"
        items={(page) => page.sessions}
        empty={
          <Empty title="No sessions match">Change the filters, or clear them to see every session.</Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Sessions"
            list="sessions"
            sorts={{ State: "state", Booked: "createdAt" }}
            columns={["Session", "State", "Template", "Renter", "Host", "Price", "Booked", ""]}
          >
            {rows.map((session) => (
              <Row key={session.id}>
                <Cell>
                  <Id value={session.id} page="session" />
                </Cell>
                <Cell>
                  <SessionState state={session.state} />
                </Cell>
                <Cell>{session.templateId}</Cell>
                <Cell>
                  <Id value={session.renterId} page="user" />
                </Cell>
                <Cell>{session.hostId ? <Id value={session.hostId} page="host" /> : "—"}</Cell>
                <Cell className="tabular">
                  {session.pricePaisePerMin == null ? "—" : `${formatPaise(session.pricePaisePerMin)}/min`}
                </Cell>
                <Cell>
                  <RelativeTime value={session.createdAt} />
                </Cell>
                <Cell>
                  <RowLink href={`/admin/sessions/?id=${session.id}`}>
                    Open<span className="sr-only"> session {session.id}</span>
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

function SessionState({ state }: { state: string }) {
  const tone = state === "RUNNING" ? "running" : state === "FAILED" ? "danger" : "plain";
  return <Pill tone={tone}>{word(state)}</Pill>;
}

function SessionRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const session = useQuery({
    queryKey: [ADMIN, "session-record", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/sessions/{id}", { params: { path: { id } } })),
    refetchInterval: (query) => (query.state.data?.settled ? false : 5_000),
  });
  const [stopping, setStopping] = useState(false);
  const running = session.data?.session.state === "RUNNING";
  usePaletteActions(
    useMemo(
      () =>
        running
          ? [{ label: "Force stop this session…", permission: "sessions.stop", run: () => setStopping(true) }]
          : [],
      [running],
    ),
  );
  return (
    <AdminPage title="Session">
      <BackLink href="/admin/sessions/">← All sessions</BackLink>
      <QueryView query={session} what="this session">
        {(found) => {
          const summary = found.session;
          return (
            <>
              <Fields
                items={[
                  [
                    "Session id",
                    <span key="id" className="tabular">
                      {summary.id}
                    </span>,
                  ],
                  ["State", <SessionState key="state" state={summary.state} />],
                  ["Template", summary.templateId],
                  [
                    "Renter",
                    <Link
                      key="renter"
                      href={`/admin/users/?id=${summary.renterId}`}
                      className="tabular underline-offset-4 hover:underline"
                    >
                      {summary.renterId}
                    </Link>,
                  ],
                  [
                    "Host",
                    summary.hostId ? (
                      <Link
                        key="host"
                        href={`/admin/hosts/?id=${summary.hostId}`}
                        className="tabular underline-offset-4 hover:underline"
                      >
                        {summary.hostId}
                      </Link>
                    ) : (
                      "—"
                    ),
                  ],
                  [
                    "Price",
                    summary.pricePaisePerMin == null
                      ? `at most ${formatPaise(summary.maxPricePaisePerMin)}/min`
                      : `${formatPaise(summary.pricePaisePerMin)}/min`,
                  ],
                  ["Spending cap", <Money key="cap" paise={summary.maxSpendPaise} />],
                  [
                    "Charged",
                    <span key="charged">
                      <Money paise={found.chargedPaise} />
                      {found.settled
                        ? ""
                        : found.accruedPaise > 0
                          ? `, and ${formatPaise(found.accruedPaise)} not settled yet`
                          : ", so far"}
                    </span>,
                  ],
                  ["Ended for", summary.errorCode ?? "—"],
                ]}
              />
              {(summary.state === "RUNNING" || summary.state === "FINISHING") && (
                <ActionButton
                  permission="sessions.stop"
                  variant="ghost"
                  className="self-start"
                  onClick={() => setStopping(true)}
                  disabled={summary.state === "FINISHING"}
                >
                  {summary.state === "FINISHING" ? "Stopping…" : "Force stop"}
                </ActionButton>
              )}
              <ActionSheet
                open={stopping}
                onOpenChange={setStopping}
                record={found.session}
                title="Force-stop this session?"
                change={`Its agent stops it at once, without a checkpoint. It settles like any stopped session: the minutes it ran are charged (${formatPaise(found.chargedPaise + found.accruedPaise)} so far), and the renter is told we stopped it.`}
                confirm="Force stop"
                destructive
                onConfirm={async (reason) => {
                  await adminData(
                    adminApi.POST("/admin/v1/sessions/{id}/stop", {
                      params: { path: { id } },
                      body: { reason },
                    }),
                  );
                  await client.invalidateQueries({ queryKey: [ADMIN] });
                }}
              />
              <Section title="Timeline">
                <Timeline events={found.timeline} />
              </Section>
              <Section title="Attempts">
                {found.attempts.length === 0 ? (
                  <Empty title="No attempts">It never reached a host.</Empty>
                ) : (
                  <Table
                    caption="Attempts"
                    columns={["Attempt", "Host", "Price", "Ran from", "Ended", "Stop asked"]}
                  >
                    {found.attempts.map((attempt) => (
                      <Row key={attempt.attempt}>
                        <Cell className="tabular">{attempt.attempt}</Cell>
                        <Cell>
                          <Id value={attempt.hostId} page="host" />
                        </Cell>
                        <Cell className="tabular">{formatPaise(attempt.pricePaisePerMin)}/min</Cell>
                        <Cell>
                          {attempt.runningAt ? <RelativeTime value={attempt.runningAt} /> : "never ran"}
                        </Cell>
                        <Cell>{attempt.endedAt ? <RelativeTime value={attempt.endedAt} /> : "—"}</Cell>
                        <Cell>{attempt.stopReason ?? "—"}</Cell>
                      </Row>
                    ))}
                  </Table>
                )}
              </Section>
            </>
          );
        }}
      </QueryView>
      <SessionTabs id={id} />
    </AdminPage>
  );
}
