import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { Link, useSearch } from "wouter";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise, parseRupees } from "@/lib/money";
import { reasonTitle } from "@/lib/reports";
import { formatDateTime } from "@/lib/time";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import {
  ADMIN,
  adminApi,
  adminData,
  type AdminDispute,
  type AdminDisputeSummary,
  type AdminUsage,
} from "./api";
import { refundProblem, witnessSummary } from "./rules";
import { SessionLog } from "./SessionLog";
import {
  AdminPage,
  BackLink,
  Cell,
  Empty,
  Fields,
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

/**
 * Reports (ADR 0028, ADR 0039): the renters' reports on their sessions, open ones first and the oldest first. One opens
 * with the session's timeline, its minutes as both witnesses saw them, what it cost and who kept what, the rating, its
 * log (read with a reason, audited) and the host's record; it's resolved once, with a refund (free credit that never
 * expires, out of the platform's commission first, then the host's share) or without, a reason the renter reads, and,
 * if the host was at fault, a flag on the host's record.
 */
export function Disputes() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <DisputeRecord id={id} /> : <DisputeQueue />;
}

const stateWords = (dispute: Pick<AdminDisputeSummary, "state">) =>
  dispute.state === "open" ? "Open" : dispute.state === "refunded" ? "Refunded" : "No refund";

function StatePill({ dispute }: { dispute: Pick<AdminDisputeSummary, "state"> }) {
  return <Pill tone={dispute.state === "open" ? "danger" : "plain"}>{stateWords(dispute)}</Pill>;
}

function DisputeQueue() {
  const [state, setState] = useState("all");
  const disputes = useInfiniteQuery({
    queryKey: [ADMIN, "disputes", state],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/disputes", {
          params: { query: { state, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Reports">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["all", "Open first, then resolved"],
            ["open", "Open only"],
            ["resolved", "Resolved"],
          ]}
        />
      </div>
      <PagedView
        query={disputes}
        what="the reports"
        items={(page) => page.disputes}
        empty={
          <Empty title={state === "resolved" ? "Nothing resolved yet" : "No reports to answer"}>
            Reports renters send from a session&apos;s screen arrive here, oldest first. Each is answered
            within 2 days.
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Reports"
            columns={["Report", "State", "Reason", "Session", "Renter", "Sent", "Refund", ""]}
          >
            {rows.map((dispute) => (
              <Row key={dispute.id}>
                <Cell>
                  <Id value={dispute.id} />
                </Cell>
                <Cell>
                  <StatePill dispute={dispute} />
                </Cell>
                <Cell>{reasonTitle(dispute.reason)}</Cell>
                <Cell>
                  <Id value={dispute.sessionId} />
                </Cell>
                <Cell>
                  <Id value={dispute.renterId} />
                </Cell>
                <Cell>
                  <RelativeTime value={dispute.createdAt} />
                </Cell>
                <Cell>{dispute.refundPaise == null ? "—" : <Money paise={dispute.refundPaise} />}</Cell>
                <Cell>
                  <RowLink href={`/admin/disputes/?id=${dispute.id}`}>
                    Open<span className="sr-only"> report {dispute.id}</span>
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

function DisputeRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const dispute = useQuery({
    queryKey: [ADMIN, "dispute", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/disputes/{id}", { params: { path: { id } } })),
  });
  const [action, setAction] = useState<"refund" | "none" | null>(null);
  const [rupees, setRupees] = useState("");
  const [flagHost, setFlagHost] = useState(false);
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  const paise = parseRupees(rupees);
  return (
    <AdminPage title="Report">
      <BackLink href="/admin/disputes/">← All reports</BackLink>
      <QueryView query={dispute} what="this report">
        {(found) => (
          <>
            <Summary found={found} />
            {found.dispute.state === "open" && (
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" onClick={() => setAction("refund")}>
                  Refund
                </Button>
                <Button variant="secondary" onClick={() => setAction("none")}>
                  No refund
                </Button>
              </div>
            )}
            <ActionSheet
              open={action === "refund"}
              onOpenChange={(next) => !next && setAction(null)}
              title="Refund this report?"
              change={
                <>
                  The amount goes to the renter&apos;s credit. It comes out of the platform&apos;s commission
                  first ({formatPaise(found.session.commissionPaise)} kept on this session), then the
                  host&apos;s share: at most {formatPaise(found.session.paidPaise)}, what the session cost
                  less anything given back. The renter reads your reason.
                </>
              }
              confirm="Refund"
              extra={
                <>
                  <SmallField label="Amount in ₹" value={rupees} onChange={setRupees} inputMode="decimal" />
                  <FlagHost found={found} checked={flagHost} onChange={setFlagHost} />
                </>
              }
              onConfirm={async (reason) => {
                const problem = refundProblem(paise, found.session.paidPaise);
                if (problem !== null || paise === null) {
                  throw new SheetProblem(problem ?? "Give an amount.");
                }
                await adminData(
                  adminApi.POST("/admin/v1/disputes/{id}/refund", {
                    params: { path: { id } },
                    body: { amountPaise: paise, reason, flagHost },
                  }),
                );
                await refresh();
              }}
            />
            <ActionSheet
              open={action === "none"}
              onOpenChange={(next) => !next && setAction(null)}
              title="Close this report without a refund?"
              change="No money moves. The renter reads your reason, so say what you found. The hold on the host's payout for this session ends."
              confirm="Close without a refund"
              extra={<FlagHost found={found} checked={flagHost} onChange={setFlagHost} />}
              onConfirm={async (reason) => {
                await adminData(
                  adminApi.POST("/admin/v1/disputes/{id}/no-refund", {
                    params: { path: { id } },
                    body: { reason, flagHost },
                  }),
                );
                await refresh();
              }}
            />
            <Evidence found={found} />
            <SessionLog sessionId={found.dispute.sessionId} purpose="judging this report" />
            <HostRecord found={found} />
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}

/** The report itself, what was decided, and how the session ended and what it cost. */
function Summary({ found }: { found: AdminDispute }) {
  const { dispute, session, rating } = found;
  return (
    <>
      <Fields
        items={[
          ["State", <StatePill key="state" dispute={dispute} />],
          ["Reason", reasonTitle(dispute.reason)],
          ["Details", dispute.details ?? "—"],
          [
            "Sent",
            <span key="sent" className="tabular">
              {formatDateTime(dispute.createdAt)}
            </span>,
          ],
          [
            "Renter",
            <Link
              key="renter"
              href={`/admin/users/?id=${dispute.renterId}`}
              className="tabular underline-offset-4 hover:underline"
            >
              {dispute.renterId}
            </Link>,
          ],
          [
            "Session",
            <Link
              key="session"
              href={`/admin/sessions/?id=${dispute.sessionId}`}
              className="tabular underline-offset-4 hover:underline"
            >
              {dispute.sessionId}
            </Link>,
          ],
          ["Session ended as", session.state],
          [
            "Ran",
            session.startedAt ? (
              <span key="ran" className="tabular">
                {formatDateTime(session.startedAt)} to {formatDateTime(session.endedAt)}
              </span>
            ) : (
              "never ran"
            ),
          ],
          [
            "Rating",
            rating ? (
              <span key="rating">
                {rating.stars} of 5{rating.comment ? `: “${rating.comment}”` : ""}
              </span>
            ) : (
              "not rated"
            ),
          ],
        ]}
      />
      <Section title="What it cost">
        <Fields
          items={[
            ["The renter paid", <Money key="paid" paise={session.paidPaise} />],
            ["The platform kept", <Money key="commission" paise={session.commissionPaise} />],
            ...session.hosts.map((host): [string, ReactNode] => [
              "A host kept",
              <span key={host.hostId}>
                <Money paise={host.paise} />{" "}
                <Link
                  href={`/admin/hosts/?id=${host.hostId}`}
                  className="tabular underline-offset-4 hover:underline"
                >
                  {host.hostId}
                </Link>
              </span>,
            ]),
          ]}
        />
        <p className="text-caption text-text-muted">
          Read now from the ledger, after anything already given back.
        </p>
      </Section>
      {dispute.state !== "open" && (
        <Section title="How it was decided">
          <Fields items={decision(dispute)} />
        </Section>
      )}
    </>
  );
}

function decision(dispute: AdminDisputeSummary): [string, ReactNode][] {
  const items: [string, ReactNode][] = [
    ["Decision", stateWords(dispute)],
    ["Reason the renter reads", dispute.resolutionReason ?? "—"],
    ["Decided by", dispute.resolvedBy ?? "—"],
    [
      "Decided",
      <span key="at" className="tabular">
        {dispute.resolvedAt ? formatDateTime(dispute.resolvedAt) : "—"}
      </span>,
    ],
  ];
  if (dispute.refundPaise != null) {
    items.push(
      ["Refunded", <Money key="refund" paise={dispute.refundPaise} />],
      ["From the commission", <Money key="from-commission" paise={dispute.commissionRefundPaise ?? 0} />],
      ["From the hosts", <Money key="from-hosts" paise={dispute.hostRefundPaise ?? 0} />],
    );
  }
  return items;
}

/** The session's timeline, and its minutes as both witnesses saw them, with its settlements and waivers. */
function Evidence({ found }: { found: AdminDispute }) {
  const sessionId = found.dispute.sessionId;
  const session = useQuery({
    queryKey: [ADMIN, "session-record", sessionId],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/sessions/{id}", { params: { path: { id: sessionId } } })),
  });
  const usage = useQuery({
    queryKey: [ADMIN, "session-usage", sessionId],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/sessions/{id}/usage", { params: { path: { id: sessionId } } })),
    // A session that never ran has no usage: the server says it isn't there, which is the answer.
    retry: false,
  });
  return (
    <>
      <Section title="Timeline">
        <QueryView query={session} what="the session's timeline" skeleton={<TimelineSkeleton />}>
          {(record) => <Timeline events={record.timeline} />}
        </QueryView>
      </Section>
      <Section title="Minutes and charges">
        {usage.isError && !usage.isFetching && found.session.startedAt == null ? (
          <Empty title="No minutes were metered">It never ran, so nothing was charged for it.</Empty>
        ) : (
          <QueryView query={usage} what="its minutes">
            {(metered) => <Minutes usage={metered} />}
          </QueryView>
        )}
      </Section>
    </>
  );
}

function TimelineSkeleton() {
  return (
    <div role="status" aria-label="Loading the session's timeline" className="flex flex-col gap-2">
      {[0, 1, 2, 3].map((key) => (
        <div key={key} aria-hidden="true" className="skeleton h-5 w-2/3 rounded-control" />
      ))}
    </div>
  );
}

function Minutes({ usage }: { usage: AdminUsage }) {
  const summary = witnessSummary(usage.minutes);
  return (
    <>
      <p className="text-small">
        <span className="tabular">{summary.total}</span> minutes: the agent reported{" "}
        <span className="tabular">{summary.agent}</span>, the lease covered{" "}
        <span className="tabular">{summary.lease}</span>, both saw{" "}
        <span className="tabular">{summary.both}</span>, <span className="tabular">{summary.charged}</span>{" "}
        were charged and <span className="tabular">{summary.waived}</span> waived.
      </p>
      {summary.unusual.length > 0 && (
        <Table
          caption="Minutes that weren't charged in the usual way"
          columns={["Minute", "Agent", "Lease", "Charged", "Waived"]}
        >
          {summary.unusual.map((minute) => (
            <Row key={`${minute.minuteIndex}-${minute.minuteAt}`}>
              <Cell className="tabular">
                <span title={formatDateTime(minute.minuteAt)}>{minute.minuteIndex}</span>
              </Cell>
              <Cell>
                {minute.agentReported ? `reported, attempt ${minute.agentAttempt}` : "didn't report"}
              </Cell>
              <Cell>{minute.leaseAlive ? `covered, attempt ${minute.leaseAttempt}` : "not covered"}</Cell>
              <Cell>{minute.billed ? "yes" : "no"}</Cell>
              <Cell>{minute.waived ? (minute.refundTxn ? "waived and refunded" : "waived") : "no"}</Cell>
            </Row>
          ))}
        </Table>
      )}
      {usage.settlements.length > 0 && (
        <Table
          caption="Settlements"
          columns={["Window", "Minutes", "Charged", "Host's share", "Commission", "Settled"]}
        >
          {usage.settlements.map((settlement) => (
            <Row key={settlement.txnId}>
              <Cell className="tabular">{settlement.windowNumber}</Cell>
              <Cell className="tabular">{settlement.minutes}</Cell>
              <Cell>
                <Money paise={settlement.amountPaise} />
              </Cell>
              <Cell>
                <Money paise={settlement.hostSharePaise} />
              </Cell>
              <Cell>
                <Money paise={settlement.commissionPaise} />
              </Cell>
              <Cell>
                <RelativeTime value={settlement.settledAt} />
              </Cell>
            </Row>
          ))}
        </Table>
      )}
      {usage.waivers.length > 0 && (
        <Table
          caption="Waivers for a lost host"
          columns={["Attempt", "Waived from", "Refunded minutes", "Refunded"]}
        >
          {usage.waivers.map((waiver) => (
            <Row key={waiver.attempt}>
              <Cell className="tabular">{waiver.attempt}</Cell>
              <Cell>
                <RelativeTime value={waiver.waivedFrom} />
              </Cell>
              <Cell className="tabular">{waiver.refundedMinutes}</Cell>
              <Cell>
                <Money paise={waiver.refundedPaise} />
              </Cell>
            </Row>
          ))}
        </Table>
      )}
    </>
  );
}

/** Whether resolving the report also marks the host's record (ADR 0039); only a session that ran had a host. */
function FlagHost({
  found,
  checked,
  onChange,
}: {
  found: AdminDispute;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  if (found.session.hostId == null) {
    return null;
  }
  return (
    <label className="flex min-h-touch items-center gap-3 text-small">
      <input
        type="checkbox"
        className="size-5"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      Flag the host&apos;s record: the host was at fault. Admins see it, and the host score uses it; the host
      doesn&apos;t.
    </label>
  );
}

/** The session's host: its record, and every flag on it from earlier reports, the newest first. */
function HostRecord({ found }: { found: AdminDispute }) {
  const hostId = found.session.hostId;
  if (hostId == null) {
    return null;
  }
  return (
    <Section title="The host's record">
      <p className="text-small">
        <Link href={`/admin/hosts/?id=${hostId}`} className="tabular underline-offset-4 hover:underline">
          {hostId}
        </Link>{" "}
        has {found.hostFlags.length === 0 ? "no flags" : `${found.hostFlags.length} flag(s)`} from reports.
      </p>
      {found.hostFlags.length > 0 && (
        <Table caption="Flags on the host's record" columns={["Flagged", "By", "Why", "Report"]}>
          {found.hostFlags.map((flag) => (
            <Row key={flag.id}>
              <Cell>
                <RelativeTime value={flag.createdAt} />
              </Cell>
              <Cell>{flag.flaggedBy}</Cell>
              <Cell>{flag.reason}</Cell>
              <Cell>
                <RowLink href={`/admin/disputes/?id=${flag.disputeId}`}>
                  Open<span className="sr-only"> report {flag.disputeId}</span>
                </RowLink>
              </Cell>
            </Row>
          ))}
        </Table>
      )}
    </Section>
  );
}
