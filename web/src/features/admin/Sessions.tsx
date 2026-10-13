import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise } from "@/lib/money";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
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
  const [form, setForm] = useState({ id: "", user, host, state: "", from: "", to: "" });
  const [asked, setAsked] = useState(form);
  const set = (field: keyof typeof form) => (value: string) => setForm({ ...form, [field]: value });
  const sessions = useInfiniteQuery({
    queryKey: [ADMIN, "sessions", asked],
    queryFn: ({ pageParam }) => {
      const from = indiaMidnight(asked.from);
      const to = indiaMidnight(asked.to);
      return adminData(
        adminApi.GET("/admin/v1/sessions", {
          params: {
            query: {
              ...(asked.id ? { id: asked.id } : {}),
              ...(asked.user ? { user: asked.user } : {}),
              ...(asked.host ? { host: asked.host } : {}),
              ...(asked.state ? { state: asked.state } : {}),
              ...(from ? { from } : {}),
              ...(to ? { to } : {}),
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
      <Filters
        onSubmit={() =>
          setAsked({ ...form, id: form.id.trim(), user: form.user.trim(), host: form.host.trim() })
        }
      >
        <SmallField label="Session id" value={form.id} onChange={set("id")} />
        <SmallField label="Renter id" value={form.user} onChange={set("user")} />
        <SmallField label="Host id" value={form.host} onChange={set("host")} />
        <SmallSelect
          label="State"
          value={form.state}
          onChange={set("state")}
          options={[["", "Any"], ...STATES.map((state): [string, string] => [state, state])]}
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
            columns={["Session", "State", "Template", "Renter", "Host", "Price", "Booked", ""]}
          >
            {rows.map((session) => (
              <Row key={session.id}>
                <Cell>
                  <Id value={session.id} />
                </Cell>
                <Cell>
                  <SessionState state={session.state} />
                </Cell>
                <Cell>{session.templateId}</Cell>
                <Cell>
                  <Id value={session.renterId} />
                </Cell>
                <Cell>{session.hostId ? <Id value={session.hostId} /> : "—"}</Cell>
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
  return <Pill tone={tone}>{state}</Pill>;
}

function SessionRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const session = useQuery({
    queryKey: [ADMIN, "session-record", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/sessions/{id}", { params: { path: { id } } })),
    refetchInterval: (query) => (query.state.data?.settled ? false : 5_000),
  });
  const [stopping, setStopping] = useState(false);
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
                <Button
                  variant="outline"
                  className="self-start"
                  onClick={() => setStopping(true)}
                  disabled={summary.state === "FINISHING"}
                >
                  {summary.state === "FINISHING" ? "Stopping…" : "Force stop"}
                </Button>
              )}
              <ActionSheet
                open={stopping}
                onOpenChange={setStopping}
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
                <ol className="flex flex-col gap-2 text-small">
                  {found.timeline.map((event, index) => (
                    <li key={`${event.at}-${index}`} className="flex flex-wrap gap-x-3">
                      <time dateTime={event.at} className="tabular text-text-muted">
                        {formatDateTime(event.at)}
                      </time>
                      <span className="font-medium">
                        {event.from ? `${event.from} → ` : ""}
                        {event.to}
                      </span>
                      <span className="text-text-muted">{event.reason}</span>
                    </li>
                  ))}
                </ol>
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
                          <Id value={attempt.hostId} />
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
    </AdminPage>
  );
}
