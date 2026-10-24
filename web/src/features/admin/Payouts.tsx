import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise } from "@/lib/money";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminPayout, type AdminPayoutMismatch } from "./api";
import {
  AdminPage,
  Cell,
  Empty,
  Fields,
  Id,
  Pill,
  QueryView,
  Row,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

/** What each kind of payout disagreement means (docs/runbooks/payouts.md, "Reconciliation"). */
export const PAYOUT_MISMATCH_WORDS: Record<string, string> = {
  missing_ours: "RazorpayX paid it; we have no payout",
  missing_theirs: "We sent it; RazorpayX doesn't have it",
  amount: "A different amount",
  status: "Ended differently",
};

const STATUS_TONE: Record<string, "running" | "danger" | "plain"> = {
  processed: "running",
  failed: "danger",
  rejected: "danger",
  cancelled: "danger",
  reversed: "danger",
};

/**
 * Payouts (ADR 0040): the balance RazorpayX has against the next run, the latest runs, every payout by status with
 * RazorpayX's reason, a run started now, one person paid again now (after a failure), and RazorpayX against our
 * payouts, a day at a time. Every action takes a reason and is audited.
 */
export function Payouts() {
  return (
    <AdminPage title="Payouts">
      <RunsAndPayouts />
      <Reconciliation />
    </AdminPage>
  );
}

function RunsAndPayouts() {
  const client = useQueryClient();
  const [status, setStatus] = useState("all");
  const [running, setRunning] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [retryUser, setRetryUser] = useState("");
  const [outcome, setOutcome] = useState<string | null>(null);
  const payouts = useQuery({
    queryKey: [ADMIN, "payouts", status],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payouts", { params: { query: { status } } })),
  });
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="Runs and payouts"
      action={
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setRetrying("")}>
            Pay one person now
          </Button>
          <Button variant="secondary" onClick={() => setRunning(true)}>
            Run now
          </Button>
        </div>
      }
    >
      <div className="flex">
        <SmallSelect
          label="Show"
          value={status}
          onChange={setStatus}
          options={[
            ["all", "All"],
            ["processing", "On their way"],
            ["queued", "Queued"],
            ["processed", "Processed"],
            ["failed", "Failed"],
            ["reversed", "Reversed"],
            ["created", "Not accepted yet"],
          ]}
        />
      </div>
      {outcome && (
        <p role="status" className="text-small">
          {outcome}
        </p>
      )}
      <QueryView query={payouts} what="the payouts">
        {(found) => (
          <>
            <Fields
              items={[
                ["Next run", formatDateTime(found.nextPayday)],
                [
                  "Balance check",
                  found.balance ? (
                    <span>
                      {found.balance.balancePaise != null ? (
                        <Money paise={found.balance.balancePaise} />
                      ) : (
                        "unknown"
                      )}{" "}
                      for <Money paise={found.balance.duePaise} /> due on {found.balance.payday}{" "}
                      <Pill tone={found.balance.isShort ? "danger" : "running"}>
                        {found.balance.isShort ? "short" : "enough"}
                      </Pill>
                      {found.balance.failure && (
                        <span className="block text-caption text-text-muted">{found.balance.failure}</span>
                      )}
                    </span>
                  ) : (
                    "not checked yet"
                  ),
                ],
              ]}
            />
            <Table
              caption="Runs"
              columns={["Payday", "Kind", "Asked by", "People", "Payouts", "Total", "Finished"]}
            >
              {found.runs.map((run) => (
                <Row key={run.id}>
                  <Cell className="tabular">{run.payday}</Cell>
                  <Cell>{run.kind}</Cell>
                  <Cell>{run.trigger}</Cell>
                  <Cell className="tabular">{run.owners ?? "—"}</Cell>
                  <Cell className="tabular">{run.payouts ?? "—"}</Cell>
                  <Cell>{run.totalPaise != null ? <Money paise={run.totalPaise} /> : "—"}</Cell>
                  <Cell>
                    {run.failure ? (
                      <span className="text-danger">{run.failure}</span>
                    ) : run.finishedAt ? (
                      <RelativeTime value={run.finishedAt} />
                    ) : (
                      "running"
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
            {found.payouts.length === 0 ? (
              <Empty title="No payouts">
                Payouts made by the weekly run, a retry or a deletion are listed here.
              </Empty>
            ) : (
              <Table
                caption="Payouts"
                columns={["Made", "Host", "Kind", "Gross", "Tax", "Net", "Status", "RazorpayX", ""]}
              >
                {found.payouts.map((payout) => (
                  <PayoutRow key={payout.id} payout={payout} onRetry={() => setRetrying(payout.userId)} />
                ))}
              </Table>
            )}
          </>
        )}
      </QueryView>
      <ActionSheet
        open={running}
        onOpenChange={setRunning}
        title="Start a payout run now?"
        change="Everyone with a checked UPI ID and at least the weekly minimum available is paid now, as Monday's run does. Nobody with a payout on its way is paid twice."
        confirm="Run it"
        onConfirm={async (reason) => {
          const run = await adminData(adminApi.POST("/admin/v1/payouts/runs", { body: { reason } }));
          setOutcome(`The run paid ${run.payouts ?? 0} people.`);
          await refresh();
        }}
      />
      <ActionSheet
        open={retrying !== null}
        onOpenChange={(next) => !next && setRetrying(null)}
        title="Pay one person now?"
        change="What they have available is paid to their UPI ID now, outside the weekly run, if they can be paid: a checked UPI ID, a verified PAN, nothing on its way."
        confirm="Pay now"
        extra={
          <SmallField
            label="Their user id"
            value={retrying === "" ? retryUser : (retrying ?? "")}
            onChange={setRetryUser}
            width="w-96"
          />
        }
        onConfirm={async (reason) => {
          const userId = retrying === "" ? retryUser.trim() : retrying!;
          const result = await adminData(
            adminApi.POST("/admin/v1/payouts/retry", { body: { userId, reason } }),
          );
          setOutcome(
            result.payout
              ? `${formatPaise(result.payout.netPaise)} sent: ${result.payout.status}.`
              : `Not paid: ${NOT_PAID[result.notPaid ?? ""] ?? result.notPaid}.`,
          );
          await refresh();
        }}
      />
    </Section>
  );
}

const NOT_PAID: Record<string, string> = {
  NO_METHOD: "no UPI ID payouts may go to",
  ON_HOLD: "their UPI ID changed less than 48 hours ago",
  NO_PAN: "no verified PAN",
  IN_PROGRESS: "a payout is already on its way",
  TOO_LITTLE: "less than ₹1 available",
};

function PayoutRow({ payout, onRetry }: { payout: AdminPayout; onRetry: () => void }) {
  const cameBack = ["failed", "rejected", "cancelled", "reversed"].includes(payout.status);
  return (
    <Row>
      <Cell>
        <RelativeTime value={payout.createdAt} />
      </Cell>
      <Cell>
        <Id value={payout.userId} />
      </Cell>
      <Cell>{payout.kind}</Cell>
      <Cell>
        <Money paise={payout.grossPaise} />
      </Cell>
      <Cell>
        <Money paise={payout.tdsPaise} />
        <span className="block text-caption text-text-muted">{payout.tdsSection}</span>
      </Cell>
      <Cell>
        <Money paise={payout.netPaise} />
      </Cell>
      <Cell>
        <Pill tone={STATUS_TONE[payout.status] ?? "plain"}>{payout.status}</Pill>
        {(payout.statusDescription ?? payout.lastError) && (
          <span className="block text-caption text-text-muted">
            {payout.statusReason ? `${payout.statusReason}: ` : ""}
            {payout.statusDescription ?? payout.lastError}
          </span>
        )}
      </Cell>
      <Cell className="tabular">
        {payout.razorpayxId ?? "—"}
        {payout.utr && <span className="block">UTR {payout.utr}</span>}
      </Cell>
      <Cell>
        {cameBack && (
          <Button variant="quiet" onClick={onRetry}>
            Retry<span className="sr-only"> paying {payout.userId}</span>
          </Button>
        )}
      </Cell>
    </Row>
  );
}

function Reconciliation() {
  const client = useQueryClient();
  const [state, setState] = useState("open");
  const [running, setRunning] = useState(false);
  const [day, setDay] = useState("");
  const [accepting, setAccepting] = useState<AdminPayoutMismatch | null>(null);
  const reconciliation = useQuery({
    queryKey: [ADMIN, "payout-reconciliation", state],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/payouts/reconciliation", { params: { query: { state } } })),
  });
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="RazorpayX against our payouts"
      action={
        <Button variant="secondary" onClick={() => setRunning(true)}>
          Run a day now
        </Button>
      }
    >
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["open", "Open"],
            ["fixed", "Fixed by a later run"],
            ["accepted", "Accepted"],
            ["all", "All"],
          ]}
        />
      </div>
      <QueryView query={reconciliation} what="the payout reconciliation">
        {(found) => (
          <>
            {found.mismatches.length === 0 ? (
              <Empty title={state === "open" ? "Nothing open" : "Nothing here"}>
                Each night at 03:45 in India, yesterday&apos;s payouts are compared with RazorpayX&apos;s;
                anything that disagrees is listed here.
              </Empty>
            ) : (
              <Table
                caption="What disagrees"
                columns={["Found", "Kind", "RazorpayX's id", "Host", "Ours", "RazorpayX's", "Detail", ""]}
              >
                {found.mismatches.map((item) => (
                  <Row key={item.id}>
                    <Cell>
                      <RelativeTime value={item.lastFoundAt} />
                    </Cell>
                    <Cell>
                      <Pill tone={item.state === "open" ? "danger" : "plain"}>
                        {PAYOUT_MISMATCH_WORDS[item.kind] ?? item.kind}
                      </Pill>
                    </Cell>
                    <Cell className="tabular">{item.subject}</Cell>
                    <Cell>{item.userId ? <Id value={item.userId} /> : "—"}</Cell>
                    <Cell>
                      {item.ourPaise != null ? <Money paise={item.ourPaise} /> : "—"} {item.ourStatus ?? ""}
                    </Cell>
                    <Cell>
                      {item.theirPaise != null ? <Money paise={item.theirPaise} /> : "—"}{" "}
                      {item.theirStatus ?? ""}
                    </Cell>
                    <Cell>
                      {item.detail}
                      {item.closeReason && (
                        <span className="block text-caption text-text-muted">
                          {item.closedBy}: {item.closeReason}
                        </span>
                      )}
                    </Cell>
                    <Cell>
                      {item.state === "open" && (
                        <Button variant="quiet" onClick={() => setAccepting(item)}>
                          Accept<span className="sr-only"> {item.subject}</span>
                        </Button>
                      )}
                    </Cell>
                  </Row>
                ))}
              </Table>
            )}
            <Table caption="Runs" columns={["Day", "Asked by", "Payouts", "Found", "Open after", "Finished"]}>
              {found.runs.map((run) => (
                <Row key={run.id}>
                  <Cell className="tabular">{run.day}</Cell>
                  <Cell>{run.trigger}</Cell>
                  <Cell className="tabular">{run.payouts ?? "—"}</Cell>
                  <Cell className="tabular">{run.found ?? "—"}</Cell>
                  <Cell className="tabular">{run.open ?? "—"}</Cell>
                  <Cell>
                    {run.failure ? (
                      <span className="text-danger">{run.failure}</span>
                    ) : run.finishedAt ? (
                      <RelativeTime value={run.finishedAt} />
                    ) : (
                      "running"
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          </>
        )}
      </QueryView>
      <ActionSheet
        open={running}
        onOpenChange={setRunning}
        title="Compare a day with RazorpayX now?"
        change="It lists that day's payouts from RazorpayX and compares each with ours, as the nightly run does. A payout ours was only behind on is moved on; nothing else moves money. Running a day again adds nothing it found before."
        confirm="Run it"
        extra={
          <SmallField label="Day in India (YYYY-MM-DD), empty for yesterday" value={day} onChange={setDay} />
        }
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/payouts/reconciliation", {
              body: { reason, ...(day.trim() ? { day: day.trim() } : {}) },
            }),
          );
          await refresh();
        }}
      />
      <ActionSheet
        open={accepting !== null}
        onOpenChange={(next) => !next && setAccepting(null)}
        title="Accept this disagreement?"
        change={
          accepting ? (
            <>
              {PAYOUT_MISMATCH_WORDS[accepting.kind] ?? accepting.kind} on {accepting.subject}:{" "}
              {accepting.detail} It closes the item; a later run that finds it again leaves it accepted. Say
              what you did about it.
            </>
          ) : null
        }
        confirm="Accept"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/payouts/mismatches/{id}/accept", {
              params: { path: { id: accepting!.id } },
              body: { reason },
            }),
          );
          await refresh();
        }}
      />
    </Section>
  );
}
