import { useQuery, type UseInfiniteQueryResult, type UseQueryResult } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData } from "./api";
import { LinkedAccounts, RecordHead, RecordNotes } from "./Notes";
import { RecordTabs, type RecordTab } from "./RecordTabs";
import { Timeline } from "./Timeline";
import { AdminPage, BackLink, Cell, Empty, Fields, Id, Pill, QueryView, Row, Section, Table } from "./ui";
import { useListQuery } from "./listView";
import { UserEnforcement } from "./Enforcement";

/** One list in a tab, with its states. */
function Listed<T, R>({
  query,
  what,
  rows,
  columns,
  row,
}: {
  query: UseQueryResult<T> | UseInfiniteQueryResult<T>;
  what: string;
  rows: (data: T) => R[];
  columns: string[];
  row: (item: R) => ReactNode;
}) {
  return (
    <QueryView
      query={query}
      what={what}
      isEmpty={(data) => rows(data).length === 0}
      empty={<Empty title="Nothing here">There&apos;s none of {what} yet.</Empty>}
    >
      {(data) => (
        <Table caption={what} columns={columns}>
          {rows(data).map(row)}
        </Table>
      )}
    </QueryView>
  );
}

const when = (value: string | null | undefined) => (value ? <RelativeTime value={value} /> : "—");

function Sessions({ user, host }: { user?: string; host?: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-sessions", user, host],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/sessions", {
          params: { query: { ...(user ? { user } : {}), ...(host ? { host } : {}) } },
        }),
      ),
  });
  return (
    <Listed
      query={query}
      what="the sessions"
      rows={(page) => page.sessions}
      columns={["Session", "Kind", "State", "Started"]}
      row={(session) => (
        <Row key={session.id}>
          <Cell>
            <Id value={session.id} page="session" />
          </Cell>
          <Cell>{session.templateId}</Cell>
          <Cell>{session.state}</Cell>
          <Cell>{when(session.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function Wallet({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-credit", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/users/{id}/credit", { params: { path: { id } } })),
  });
  return (
    <QueryView query={query} what="their credit">
      {(credit) => (
        <div className="flex flex-col gap-4">
          <Fields
            items={[
              ["Balance", <Money key="balance" paise={credit.balancePaise} />],
              ["Reserved", <Money key="reserved" paise={credit.reservedPaise} />],
            ]}
          />
          <Lots lots={credit.lots} />
        </div>
      )}
    </QueryView>
  );
}

function Lots({
  lots,
}: {
  lots: {
    id: string;
    kind: string;
    sourceType: string;
    amountPaise: number;
    remainingPaise: number;
    expiresAt?: string | null;
    createdAt: string;
  }[];
}) {
  return lots.length === 0 ? (
    <Empty title="No credit lots">Credit they&apos;re given or buy is listed here.</Empty>
  ) : (
    <Table caption="Credit lots" columns={["Kind", "From", "Amount", "Left", "Expires", "Made"]}>
      {lots.map((lot) => (
        <Row key={lot.id}>
          <Cell>{lot.kind}</Cell>
          <Cell>{lot.sourceType}</Cell>
          <Cell>
            <Money paise={lot.amountPaise} />
          </Cell>
          <Cell>
            <Money paise={lot.remainingPaise} />
          </Cell>
          <Cell>{when(lot.expiresAt)}</Cell>
          <Cell>{when(lot.createdAt)}</Cell>
        </Row>
      ))}
    </Table>
  );
}

function Payments({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-payments", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payments", { params: { query: { query: id } } })),
  });
  return (
    <Listed
      query={query}
      what="their payments"
      rows={(list) => list.payments}
      columns={["Payment", "Amount", "Status", "When"]}
      row={(payment) => (
        <Row key={payment.id}>
          <Cell>
            <Id value={payment.id} page="payment" />
          </Cell>
          <Cell>
            <Money paise={payment.amountPaise} />
          </Cell>
          <Cell>{payment.status}</Cell>
          <Cell>{when(payment.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function Refunds({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-refunds", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/refunds", { params: { query: { user: id } } })),
  });
  return (
    <Listed
      query={query}
      what="their refunds"
      rows={(list) => list.refunds}
      columns={["Refund", "Asked", "Status", "When"]}
      row={(refund) => (
        <Row key={refund.id}>
          <Cell>
            <Id value={refund.id} page="refund" />
          </Cell>
          <Cell>{refund.requestedPaise ? <Money paise={refund.requestedPaise} /> : "All they can"}</Cell>
          <Cell>{refund.status}</Cell>
          <Cell>{when(refund.requestedAt)}</Cell>
        </Row>
      )}
    />
  );
}

function Invoices({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-invoices", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/invoices", { params: { query: { userId: id } } })),
  });
  return (
    <Listed
      query={query}
      what="their invoices"
      rows={(list) => list.invoices}
      columns={["Number", "Kind", "Total", "Issued"]}
      row={(item) => (
        <Row key={item.invoice.id}>
          <Cell>
            <Id value={item.invoice.id} page="invoice" label={item.invoice.number} />
          </Cell>
          <Cell>{item.invoice.kind}</Cell>
          <Cell>
            <Money paise={item.invoice.totalPaise} />
          </Cell>
          <Cell>{when(item.invoice.issuedAt)}</Cell>
        </Row>
      )}
    />
  );
}

function OwnedHosts({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-hosts", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/hosts", { params: { query: { query: id } } })),
  });
  return (
    <Listed
      query={query}
      what="the hosts they own"
      rows={(page) => page.hosts}
      columns={["Host", "Name", "Status", "Linked"]}
      row={(host) => (
        <Row key={host.id}>
          <Cell>
            <Id value={host.id} page="host" />
          </Cell>
          <Cell>{host.name}</Cell>
          <Cell>{host.status}</Cell>
          <Cell>{when(host.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function PayoutsAndChecks({ id, canCheck, canPay }: { id: string; canCheck: boolean; canPay: boolean }) {
  const payouts = useQuery({
    queryKey: [ADMIN, "record-payouts", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payouts", { params: { query: { user: id } } })),
    enabled: canPay,
  });
  const details = useQuery({
    queryKey: [ADMIN, "record-payout-details", id],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/users/{id}/payout-details", { params: { path: { id } } })),
    enabled: canCheck,
  });
  return (
    <div className="flex flex-col gap-6">
      {canCheck && (
        <Section title="PAN and UPI IDs">
          <QueryView query={details} what="how they're paid">
            {(found) => (
              <div className="flex flex-col gap-3">
                <Fields
                  items={[["PAN", found.pan ? `Ending ${found.pan.last4}, ${found.pan.state}` : "Not given"]]}
                />
                {found.methods.length > 0 && (
                  <Table caption="UPI IDs" columns={["UPI ID", "State", "Checked", "Ended"]}>
                    {found.methods.map((method) => (
                      <Row key={method.id}>
                        <Cell>{method.vpa}</Cell>
                        <Cell>{method.state}</Cell>
                        <Cell>{when(method.checkedAt)}</Cell>
                        <Cell>{when(method.endedAt)}</Cell>
                      </Row>
                    ))}
                  </Table>
                )}
              </div>
            )}
          </QueryView>
        </Section>
      )}
      {canPay && (
        <Section title="Payouts">
          <Listed
            query={payouts}
            what="their payouts"
            rows={(page) => page.payouts}
            columns={["Payout", "Net", "Status", "When"]}
            row={(payout) => (
              <Row key={payout.id}>
                <Cell>
                  <Id value={payout.id} page="payout" />
                </Cell>
                <Cell>
                  <Money paise={payout.netPaise} />
                </Cell>
                <Cell>{payout.status}</Cell>
                <Cell>{when(payout.createdAt)}</Cell>
              </Row>
            )}
          />
        </Section>
      )}
    </div>
  );
}

function Tickets({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-tickets", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/tickets", { params: { query: { user: id } } })),
  });
  return (
    <Listed
      query={query}
      what="their tickets"
      rows={(page) => page.tickets}
      columns={["Ticket", "Topic", "Status", "Opened"]}
      row={(ticket) => (
        <Row key={ticket.id}>
          <Cell>
            <Id value={ticket.id} page="ticket" label={`#${ticket.number}`} />
          </Cell>
          <Cell>{ticket.topic}</Cell>
          <Cell>{ticket.status}</Cell>
          <Cell>{when(ticket.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function Reports({ renter, session }: { renter?: string; session?: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-reports", renter, session],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/disputes", {
          params: { query: { ...(renter ? { renter } : {}), ...(session ? { session } : {}) } },
        }),
      ),
  });
  return (
    <Listed
      query={query}
      what="the reports"
      rows={(page) => page.disputes}
      columns={["Report", "Reason", "State", "When"]}
      row={(dispute) => (
        <Row key={dispute.id}>
          <Cell>
            <Id value={dispute.id} page="report" />
          </Cell>
          <Cell>{dispute.reason}</Cell>
          <Cell>{dispute.state}</Cell>
          <Cell>{when(dispute.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function AbuseItems({ renter, host }: { renter?: string; host?: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-abuse", renter, host],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/abuse", {
          params: { query: { state: "all", ...(renter ? { renter } : {}), ...(host ? { host } : {}) } },
        }),
      ),
  });
  return (
    <Listed
      query={query}
      what="what abuse checks found"
      rows={(page) => page.items}
      columns={["Item", "Found", "State", "When"]}
      row={(item) => (
        <Row key={item.id}>
          <Cell>
            <Id value={item.id} page="abuse" />
          </Cell>
          <Cell>{item.kinds.join(", ") || item.action}</Cell>
          <Cell>{item.state}</Cell>
          <Cell>{when(item.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function SignIns({ id }: { id: string }) {
  const query = useListQuery([ADMIN, "record-signins", id], "events", (cursor?: string) =>
    adminData(
      adminApi.GET("/admin/v1/users/{id}/security-events", {
        params: { path: { id }, query: cursor ? { cursor } : {} },
      }),
    ),
  );
  return (
    <Listed
      query={query}
      what="their sign-ins and devices"
      rows={(page) => page.events}
      columns={["What", "Detail", "From", "When"]}
      row={(event) => (
        <Row key={event.id}>
          <Cell>{event.kind.replace(/_/g, " ")}</Cell>
          <Cell>{event.detail ?? "—"}</Cell>
          <Cell>{event.address ?? "—"}</Cell>
          <Cell>{when(event.at)}</Cell>
        </Row>
      )}
    />
  );
}

function AuditAbout({ type, id }: { type: string; id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-audit", type, id],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/audit", { params: { query: { targetType: type, targetId: id } } })),
  });
  return (
    <Listed
      query={query}
      what="what admins did"
      rows={(page) => page.entries}
      columns={["Action", "By", "Why", "When"]}
      row={(entry) => (
        <Row key={entry.id}>
          <Cell>{entry.action}</Cell>
          <Cell>{entry.actorName}</Cell>
          <Cell>{entry.reason}</Cell>
          <Cell>{when(entry.createdAt)}</Cell>
        </Row>
      )}
    />
  );
}

function WhoViewed({ id }: { id: string }) {
  const query = useListQuery([ADMIN, "record-viewers", id], "entries", (cursor?: string) =>
    adminData(
      adminApi.GET("/admin/v1/personal-data/access-log", {
        params: { query: { subject: id, ...(cursor ? { cursor } : {}) } },
      }),
    ),
  );
  return (
    <Listed
      query={query}
      what="who viewed their data"
      rows={(page) => page.entries}
      columns={["Admin", "Field", "Why", "When"]}
      row={(entry) => (
        <Row key={entry.id}>
          <Cell>
            <Id value={entry.adminId} page="user" />
          </Cell>
          <Cell>{entry.field}</Cell>
          <Cell>{entry.reason}</Cell>
          <Cell>{when(entry.at)}</Cell>
        </Row>
      )}
    />
  );
}

/** A person's page (ADR 0052): everything about them, each tab read through the module that owns it. */
export function UserTabs({ id, can }: { id: string; can: (permission: string) => boolean }) {
  const tabs: RecordTab[] = [
    { id: "timeline", label: "Timeline", needs: "", render: () => <Timeline subject="user" id={id} /> },
    { id: "wallet", label: "Wallet", needs: "money.read", render: () => <Wallet id={id} /> },
    { id: "sessions", label: "Sessions", needs: "sessions.read", render: () => <Sessions user={id} /> },
    { id: "payments", label: "Payments", needs: "payments.read", render: () => <Payments id={id} /> },
    { id: "refunds", label: "Refunds", needs: "refunds.read", render: () => <Refunds id={id} /> },
    { id: "invoices", label: "Invoices", needs: "invoices.read", render: () => <Invoices id={id} /> },
    { id: "hosts", label: "Hosts owned", needs: "hosts.read", render: () => <OwnedHosts id={id} /> },
    {
      id: "enforcement",
      label: "Enforcement",
      needs: "enforcement.read",
      render: () => <UserEnforcement id={id} />,
    },
    ...(can("payouts.read") || can("verifications.read")
      ? [
          {
            id: "payouts",
            label: "Payouts",
            needs: "",
            render: () => (
              <PayoutsAndChecks id={id} canCheck={can("verifications.read")} canPay={can("payouts.read")} />
            ),
          },
        ]
      : []),
    { id: "tickets", label: "Tickets", needs: "tickets.read", render: () => <Tickets id={id} /> },
    { id: "reports", label: "Reports", needs: "disputes.read", render: () => <Reports renter={id} /> },
    { id: "abuse", label: "Abuse", needs: "abuse.read", render: () => <AbuseItems renter={id} /> },
    { id: "sign-ins", label: "Sign-ins", needs: "users.read", render: () => <SignIns id={id} /> },
    { id: "audit", label: "Audit", needs: "audit.read", render: () => <AuditAbout type="user" id={id} /> },
    {
      id: "notes",
      label: "Notes",
      needs: "notes.read",
      render: () => <RecordNotes subject="user" id={id} />,
    },
    { id: "linked", label: "Linked accounts", needs: "trust.read", render: () => <LinkedAccounts id={id} /> },
    { id: "viewed", label: "Who viewed", needs: "audit.read", render: () => <WhoViewed id={id} /> },
  ];
  return (
    <div className="flex flex-col gap-4">
      <RecordHead subject="user" id={id} />
      <RecordTabs label="About this person" tabs={tabs} />
    </div>
  );
}

function HostHistory({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-host-history", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/hosts/{id}/history", { params: { path: { id } } })),
  });
  return (
    <QueryView query={query} what="this host's history">
      {(history) => (
        <div className="flex flex-col gap-6">
          <Section title="Score, a day">
            {history.scores.length === 0 ? (
              <Empty title="No score yet">A host is scored once it has run or tested.</Empty>
            ) : (
              <Table caption="Score, a day" columns={["Day", "Reliability", "Bandwidth", "Penalty"]}>
                {history.scores.map((score) => (
                  <Row key={score.day}>
                    <Cell>{when(score.day)}</Cell>
                    <Cell>{score.reliability}</Cell>
                    <Cell>{score.bandwidth}</Cell>
                    <Cell>{score.penalty}</Cell>
                  </Row>
                ))}
              </Table>
            )}
          </Section>
          <Section title="Self-tests">
            {history.selfTests.length === 0 ? (
              <Empty title="No self-tests">An admin can run one from this page.</Empty>
            ) : (
              <Table caption="Self-tests" columns={["Run", "State", "Asked", "Finished"]}>
                {history.selfTests.map((run) => (
                  <Row key={run.id}>
                    <Cell>
                      <Id value={run.id} />
                    </Cell>
                    <Cell>{run.state}</Cell>
                    <Cell>{when(run.requestedAt)}</Cell>
                    <Cell>{when(run.finishedAt)}</Cell>
                  </Row>
                ))}
              </Table>
            )}
          </Section>
        </div>
      )}
    </QueryView>
  );
}

function HostEarnings({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-host-earnings", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/hosts/{id}/earnings", { params: { path: { id } } })),
  });
  return (
    <QueryView query={query} what="what this host earned">
      {(earned) => (
        <Fields
          items={[
            ["Charged to renters", <Money key="charged" paise={earned.chargedPaise} />],
            ["The host's share", <Money key="share" paise={earned.hostSharePaise} />],
            ["Minutes", earned.minutes],
          ]}
        />
      )}
    </QueryView>
  );
}

/** A host's page (ADR 0052). */
export function HostTabs({ id }: { id: string }) {
  return (
    <div className="flex flex-col gap-4">
      <RecordHead subject="host" id={id} />
      <RecordTabs
        label="About this host"
        tabs={[
          { id: "timeline", label: "Timeline", needs: "", render: () => <Timeline subject="host" id={id} /> },
          {
            id: "history",
            label: "Score and tests",
            needs: "hosts.read",
            render: () => <HostHistory id={id} />,
          },
          { id: "sessions", label: "Sessions", needs: "sessions.read", render: () => <Sessions host={id} /> },
          { id: "earnings", label: "Earnings", needs: "money.read", render: () => <HostEarnings id={id} /> },
          {
            id: "abuse",
            label: "Blocklist and abuse",
            needs: "abuse.read",
            render: () => <AbuseItems host={id} />,
          },
          {
            id: "notes",
            label: "Notes",
            needs: "notes.read",
            render: () => <RecordNotes subject="host" id={id} />,
          },
          {
            id: "audit",
            label: "Audit",
            needs: "audit.read",
            render: () => <AuditAbout type="host" id={id} />,
          },
        ]}
      />
    </div>
  );
}

function SessionLife({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-session-life", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/sessions/{id}/life", { params: { path: { id } } })),
  });
  return (
    <Listed
      query={query}
      what="this session's attempts"
      rows={(life) => life.attempts}
      columns={[
        "Try",
        "Host",
        "Matched",
        "Told to start",
        "Running",
        "Ended",
        "Why it ended",
        "Lease reached",
        "Renewals",
      ]}
      row={(attempt) => (
        <Row key={attempt.attempt}>
          <Cell>{attempt.attempt}</Cell>
          <Cell>
            <Id value={attempt.hostId} page="host" />
          </Cell>
          <Cell>{when(attempt.matchedAt)}</Cell>
          <Cell>{when(attempt.startDeliveredAt)}</Cell>
          <Cell>{when(attempt.runningAt)}</Cell>
          <Cell>{when(attempt.endedAt)}</Cell>
          <Cell>{attempt.lostReason ?? attempt.stopReason ?? "—"}</Cell>
          <Cell>{when(attempt.leaseCoveredUntil)}</Cell>
          <Cell>{attempt.leaseStretches}</Cell>
        </Row>
      )}
    />
  );
}

function SessionMinutes({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-session-minutes", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/sessions/{id}/usage", { params: { path: { id } } })),
    retry: false,
  });
  return (
    <Listed
      query={query}
      what="this session's minutes"
      rows={(usage) => usage.minutes}
      columns={["Minute", "Agent reported", "Lease alive", "Billed"]}
      row={(minute) => (
        <Row key={minute.minuteIndex}>
          <Cell>{minute.minuteIndex}</Cell>
          <Cell>{minute.agentReported ? "Yes" : <Pill tone="danger">No</Pill>}</Cell>
          <Cell>{minute.leaseAlive ? "Yes" : <Pill tone="danger">No</Pill>}</Cell>
          <Cell>{minute.billed ? "Yes" : "No"}</Cell>
        </Row>
      )}
    />
  );
}

function SessionCommands({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-session-commands", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/sessions/{id}/commands", { params: { path: { id } } })),
  });
  return (
    <Listed
      query={query}
      what="the commands sent to agents"
      rows={(found) => found.commands}
      columns={["Command", "Status", "Tries", "Sent", "Acknowledged"]}
      row={(command) => (
        <Row key={command.id}>
          <Cell>{command.type}</Cell>
          <Cell>{command.status}</Cell>
          <Cell>{command.attempts}</Cell>
          <Cell>{when(command.sentAt)}</Cell>
          <Cell>{when(command.ackedAt)}</Cell>
        </Row>
      )}
    />
  );
}

function SessionRatings({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-session-ratings", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/ratings", { params: { query: { session: id } } })),
  });
  return (
    <Listed
      query={query}
      what="its rating"
      rows={(page) => page.ratings}
      columns={["Stars", "Comment", "When"]}
      row={(rating) => (
        <Row key={rating.sessionId}>
          <Cell>{rating.stars}</Cell>
          <Cell>{rating.comment ?? "—"}</Cell>
          <Cell>{when(rating.ratedAt)}</Cell>
        </Row>
      )}
    />
  );
}

/** A session's page (ADR 0052): its log, with its own permission, stays on the page above. */
export function SessionTabs({ id }: { id: string }) {
  return (
    <div className="flex flex-col gap-4">
      <RecordHead subject="session" id={id} />
      <RecordTabs
        label="About this session"
        tabs={[
          {
            id: "timeline",
            label: "Timeline",
            needs: "",
            render: () => <Timeline subject="session" id={id} />,
          },
          {
            id: "life",
            label: "Attempts and lease",
            needs: "sessions.read",
            render: () => <SessionLife id={id} />,
          },
          {
            id: "minutes",
            label: "Minutes",
            needs: "sessions.read",
            render: () => <SessionMinutes id={id} />,
          },
          {
            id: "commands",
            label: "Commands",
            needs: "sessions.read",
            render: () => <SessionCommands id={id} />,
          },
          { id: "rating", label: "Rating", needs: "ratings.read", render: () => <SessionRatings id={id} /> },
          { id: "reports", label: "Reports", needs: "disputes.read", render: () => <Reports session={id} /> },
          {
            id: "notes",
            label: "Notes",
            needs: "notes.read",
            render: () => <RecordNotes subject="session" id={id} />,
          },
          {
            id: "audit",
            label: "Audit",
            needs: "audit.read",
            render: () => <AuditAbout type="session" id={id} />,
          },
        ]}
      />
    </div>
  );
}

/** A payment's page (ADR 0052): what Razorpay said, the credit it became, and everything made from it. */
export function PaymentRecord({ id }: { id: string }) {
  const query = useQuery({
    queryKey: [ADMIN, "record-payment", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payments/{id}", { params: { path: { id } } })),
  });
  return (
    <div className="flex flex-col gap-6">
      <QueryView query={query} what="this payment">
        {(payment) => (
          <div className="flex flex-col gap-6">
            <RecordHead subject="payment" id={id} />
            <div className="flex flex-wrap items-center gap-2">
              <Money paise={payment.amountPaise} />
              <Pill
                tone={
                  payment.status === "paid" ? "running" : payment.status === "failed" ? "danger" : "plain"
                }
              >
                {payment.status}
              </Pill>
            </div>
            <Fields
              items={[
                ["Person", <Id key="user" value={payment.userId} page="user" />],
                ["Razorpay payment", payment.paymentId ?? "—"],
                ["Razorpay order", payment.orderId],
                [
                  "How",
                  [payment.method, payment.cardNetwork, payment.cardLast4].filter(Boolean).join(" ") || "—",
                ],
                ["Failure", payment.failureReason ?? "—"],
                ["Credited by", payment.creditedBy ?? "—"],
                ["Asked", when(payment.createdAt)],
                ["Paid", when(payment.paidAt)],
              ]}
            />
            <RecordTabs
              label="About this payment"
              tabs={[
                {
                  id: "timeline",
                  label: "Timeline",
                  needs: "",
                  render: () => <Timeline subject="payment" id={id} />,
                },
                {
                  id: "notes",
                  label: "Notes",
                  needs: "notes.read",
                  render: () => <RecordNotes subject="payment" id={id} />,
                },
                {
                  id: "credit",
                  label: "Credit",
                  needs: "payments.read",
                  render: () => <Lots lots={payment.lots} />,
                },
                {
                  id: "refunds",
                  label: "Refunds",
                  needs: "payments.read",
                  render: () =>
                    payment.refunds.length === 0 ? (
                      <Empty title="No refunds">None of this payment has gone back.</Empty>
                    ) : (
                      <Table caption="Refunds" columns={["Refund", "Amount", "Razorpay's", "Status"]}>
                        {payment.refunds.map((part) => (
                          <Row key={`${part.refundId}${part.razorpayRefundId ?? ""}`}>
                            <Cell>
                              <Id value={part.refundId} page="refund" />
                            </Cell>
                            <Cell>
                              <Money paise={part.amountPaise} />
                            </Cell>
                            <Cell>{part.razorpayRefundId ?? "—"}</Cell>
                            <Cell>{part.status}</Cell>
                          </Row>
                        ))}
                      </Table>
                    ),
                },
                {
                  id: "documents",
                  label: "Invoices",
                  needs: "payments.read",
                  render: () =>
                    payment.documents.length === 0 ? (
                      <Empty title="No documents">A paid top-up gets one.</Empty>
                    ) : (
                      <Table caption="Invoices" columns={["Number", "Kind"]}>
                        {payment.documents.map((document) => (
                          <Row key={document.id}>
                            <Cell>
                              <Id value={document.id} page="invoice" label={document.number} />
                            </Cell>
                            <Cell>{document.kind}</Cell>
                          </Row>
                        ))}
                      </Table>
                    ),
                },
                {
                  id: "chargebacks",
                  label: "Chargebacks",
                  needs: "payments.read",
                  render: () =>
                    payment.chargebacks.length === 0 ? (
                      <Empty title="No chargebacks">None against this payment.</Empty>
                    ) : (
                      <Table caption="Chargebacks" columns={["Chargeback", "Amount", "Status"]}>
                        {payment.chargebacks.map((back) => (
                          <Row key={back.id}>
                            <Cell>
                              <Id value={back.id} page="chargeback" label={back.disputeId} />
                            </Cell>
                            <Cell>
                              <Money paise={back.amountPaise} />
                            </Cell>
                            <Cell>{back.status}</Cell>
                          </Row>
                        ))}
                      </Table>
                    ),
                },
                {
                  id: "reconciliation",
                  label: "Reconciliation",
                  needs: "payments.read",
                  render: () =>
                    payment.findings.length === 0 ? (
                      <Empty title="Nothing found">
                        The reconciliation has no finding about this payment.
                      </Empty>
                    ) : (
                      <Table caption="Reconciliation" columns={["Found", "State"]}>
                        {payment.findings.map((finding) => (
                          <Row key={finding.id}>
                            <Cell>{finding.kind.replace(/_/g, " ")}</Cell>
                            <Cell>{finding.state}</Cell>
                          </Row>
                        ))}
                      </Table>
                    ),
                },
              ]}
            />
          </div>
        )}
      </QueryView>
    </div>
  );
}

/** A word from a field's name: {@code createdAt} reads "Created at". */
const fieldName = (name: string) => {
  const spaced = name.replace(/([A-Z])/g, " $1").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
};

const ID_FIELDS: Record<string, string> = {
  userId: "user",
  ownerId: "user",
  renterId: "user",
  hostId: "host",
  sessionId: "session",
  ticketId: "ticket",
  topUpId: "payment",
};

/** What a record says, field by field, for one that has nothing but its fields to show. */
function FieldsOf({ record }: { record: Record<string, unknown> }) {
  return (
    <Fields
      items={Object.entries(record)
        .filter(([, value]) => value === null || typeof value !== "object")
        .map(([name, value]): [string, ReactNode] => [
          fieldName(name),
          value === null || value === undefined ? (
            "—"
          ) : ID_FIELDS[name] && typeof value === "string" ? (
            <Id key={name} value={value} page={ID_FIELDS[name]} />
          ) : (
            String(value as string | number | boolean)
          ),
        ])}
    />
  );
}

/** The page of a refund, a document, a payout or an image: what the module says of it. */
export function PlainRecord({
  title,
  back,
  backLabel,
  read,
  page,
  id,
}: {
  title: string;
  back: string;
  backLabel: string;
  read: () => Promise<unknown>;
  page: string;
  id: string;
}) {
  const query = useQuery({ queryKey: [ADMIN, "plain-record", page, id], queryFn: read });
  return (
    <AdminPage title={title}>
      <BackLink href={back}>{backLabel}</BackLink>
      <QueryView query={query} what={`this ${title.toLowerCase()}`}>
        {(found) => <FieldsOf record={found as Record<string, unknown>} />}
      </QueryView>
    </AdminPage>
  );
}
