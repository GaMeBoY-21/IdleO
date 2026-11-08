import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminPayout, type AdminPayoutMismatch } from "./api";
import { word } from "./words";
import {
  AdminPage,
  Cell,
  Empty,
  Fields,
  Id,
  PagedView,
  Pill,
  QueryView,
  Row,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";
import { ActionButton } from "./permissions";
import { PlainRecord } from "./Records";
import { ListBar } from "./Lists";
import { ListScope, useListParam, useListQuery, useListView } from "./listView";

const MISMATCH_COLUMNS = ["Found", "Kind", "RazorpayX's id", "Host", "Ours", "RazorpayX's", "Detail", ""];

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
 * Payouts (ADR 0040, ADR 0055): the balance RazorpayX has against the next run, the latest batches (each a preview
 * released by approval, as previewed), every payout by status with RazorpayX's reason, a batch prepared now, one person
 * paid again now (after a failure), and RazorpayX against our payouts, a day at a time. Every action takes a reason and
 * is audited.
 */
export function Payouts() {
  const search = new URLSearchParams(useSearch());
  const id = search.get("id");
  const batch = search.get("batch");
  if (batch) {
    return (
      <AdminPage title="Payout batch">
        <Link href="/admin/payouts/" className="text-small underline">
          ← All runs and payouts
        </Link>
        <Batch id={batch} />
      </AdminPage>
    );
  }
  return id ? (
    <PlainRecord
      title="Payout"
      back="/admin/payouts/"
      backLabel="← All payouts"
      page="payout"
      id={id}
      read={() => adminData(adminApi.GET("/admin/v1/payouts/{id}", { params: { path: { id } } }))}
    />
  ) : (
    <PayoutList />
  );
}

function PayoutList() {
  return (
    <AdminPage title="Payouts">
      <RunsAndPayouts />
      <ListScope.Provider value="recon">
        <Reconciliation />
      </ListScope.Provider>
    </AdminPage>
  );
}

function RunsAndPayouts() {
  const client = useQueryClient();
  const [status, setStatus] = useListParam("status", "all");
  const view = useListView();
  const [running, setRunning] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [retryUser, setRetryUser] = useState("");
  const [outcome, setOutcome] = useState<string | null>(null);
  // The runs and balance come with any page: a page of one is enough for them.
  const payouts = useQuery({
    queryKey: [ADMIN, "payouts", "summary"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payouts", { params: { query: { limit: 1 } } })),
  });
  const list = useInfiniteQuery({
    queryKey: [ADMIN, "payouts", status, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/payouts", {
          params: { query: { status, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="Runs and payouts"
      action={
        <div className="flex gap-2">
          <ActionButton permission="payouts.release" variant="ghost" onClick={() => setRetrying("")}>
            Pay one person now
          </ActionButton>
          <ActionButton permission="payouts.run" variant="ghost" onClick={() => setRunning(true)}>
            Prepare a batch now
          </ActionButton>
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
                        <span className="block text-caption text-text-2">{found.balance.failure}</span>
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
              columns={["Payday", "Kind", "Asked by", "People", "Payouts", "Total", "Batch", "Finished"]}
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
                    <Link href={`/admin/payouts/?batch=${run.id}`} className="underline">
                      {run.state === "preview" ? "Review and release" : "Released"}
                    </Link>
                  </Cell>
                  <Cell>
                    {run.failure ? (
                      <span className="text-danger">{word(run.failure)}</span>
                    ) : run.finishedAt ? (
                      <RelativeTime value={run.finishedAt} />
                    ) : (
                      "running"
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
            <ListBar list="payouts" filters={{ status: "status" }} />
            <PagedView
              query={list}
              what="the payouts"
              items={(page) => page.payouts}
              empty={
                <Empty title="No payouts">
                  Payouts made by the weekly run, a retry or a deletion are listed here.
                </Empty>
              }
            >
              {(rows) => (
                <Table
                  caption="Payouts"
                  list="payouts"
                  sorts={{ Made: "createdAt", Net: "net", Status: "status" }}
                  columns={["Made", "Host", "Kind", "Gross", "Tax", "Net", "Status", "RazorpayX", ""]}
                >
                  {rows.map((payout) => (
                    <PayoutRow key={payout.id} payout={payout} onRetry={() => setRetrying(payout.userId)} />
                  ))}
                </Table>
              )}
            </PagedView>
          </>
        )}
      </QueryView>
      <ActionSheet
        open={running}
        onOpenChange={setRunning}
        title="Prepare a payout batch now?"
        change="A preview of everyone with a checked UPI ID and at least the weekly minimum available, as Monday's batch is: nothing is paid until another admin approves its release."
        confirm="Prepare it"
        onConfirm={async (reason) => {
          const run = await adminData(adminApi.POST("/admin/v1/payouts/runs", { body: { reason } }));
          setOutcome(`The batch previews ${run.payouts ?? 0} payouts; review and release it to pay them.`);
          await refresh();
        }}
      />
      <ActionSheet
        open={retrying !== null}
        onOpenChange={(next) => !next && setRetrying(null)}
        typed={retrying ? retrying.slice(0, 8) : undefined}
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
          // It goes to another admin; what became of it is on the Approvals screen.
          await adminData(adminApi.POST("/admin/v1/payouts/retry", { body: { userId, reason } }));
          await refresh();
        }}
      />
    </Section>
  );
}

function PayoutRow({ payout, onRetry }: { payout: AdminPayout; onRetry: () => void }) {
  const cameBack = ["failed", "rejected", "cancelled", "reversed"].includes(payout.status);
  return (
    <Row>
      <Cell>
        <RelativeTime value={payout.createdAt} />
      </Cell>
      <Cell>
        <Id value={payout.userId} page="user" />
      </Cell>
      <Cell>{payout.kind}</Cell>
      <Cell>
        <Money paise={payout.grossPaise} />
      </Cell>
      <Cell>
        <Money paise={payout.tdsPaise} />
        <span className="block text-caption text-text-2">{payout.tdsSection}</span>
      </Cell>
      <Cell>
        <Money paise={payout.netPaise} />
      </Cell>
      <Cell>
        <Pill tone={STATUS_TONE[payout.status] ?? "plain"}>{payout.status}</Pill>
        {(payout.statusDescription ?? payout.lastError) && (
          <span className="block text-caption text-text-2">
            {payout.statusReason ? `${word(payout.statusReason ?? "")}: ` : ""}
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
          <ActionButton permission="payouts.release" variant="quiet" onClick={onRetry}>
            Retry<span className="sr-only"> paying {payout.userId}</span>
          </ActionButton>
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
  const view = useListView();
  const reconciliation = useListQuery(
    [ADMIN, "payout-reconciliation", state, view],
    "mismatches",
    (cursor?: string) =>
      adminData(
        adminApi.GET("/admin/v1/payouts/reconciliation", {
          params: { query: { state, ...view, ...(cursor ? { cursor } : {}) } },
        }),
      ),
  );
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="RazorpayX against our payouts"
      action={
        <ActionButton permission="reconciliation.run" variant="ghost" onClick={() => setRunning(true)}>
          Run a day now
        </ActionButton>
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
                sorts={{ Found: "foundAt", Kind: "kind" }}
                columns={MISMATCH_COLUMNS}
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
                    <Cell>{item.userId ? <Id value={item.userId} page="user" /> : "—"}</Cell>
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
                        <span className="block text-caption text-text-2">
                          {item.closedBy}: {item.closeReason}
                        </span>
                      )}
                    </Cell>
                    <Cell>
                      {item.state === "open" && (
                        <ActionButton
                          permission="reconciliation.accept"
                          variant="quiet"
                          onClick={() => setAccepting(item)}
                        >
                          Accept<span className="sr-only"> {item.subject}</span>
                        </ActionButton>
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
                      <span className="text-danger">{word(run.failure)}</span>
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
        record={accepting ?? undefined}
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

const BATCH_COLUMNS = ["Host", "Gross", "Recovered", "Held", "Tax", "Fee", "Net", "UPI ID", "Paid"];

/**
 * One batch (ADR 0055): each host as previewed, or why they're left out, and once released each payout's status. Its
 * CSV is the batch report for the CA. Refreshing makes the preview again from now; releasing goes to another admin and
 * pays each host as previewed, once.
 */
function Batch({ id }: { id: string }) {
  const client = useQueryClient();
  const [acting, setActing] = useState<"refresh" | "release" | null>(null);
  const batch = useQuery({
    queryKey: [ADMIN, "payout-batch", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payouts/batches/{id}", { params: { path: { id } } })),
  });
  const view = useListView();
  const items = useInfiniteQuery({
    queryKey: [ADMIN, "payout-batch-items", id, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/payouts/batch-items", {
          params: { query: { batch: id, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <QueryView query={batch} what="the batch">
      {(run) => (
        <Section
          title={`The batch of ${run.payday}`}
          action={
            run.state === "preview" ? (
              <div className="flex gap-2">
                <ActionButton permission="payouts.run" variant="ghost" onClick={() => setActing("refresh")}>
                  Refresh the preview
                </ActionButton>
                <ActionButton permission="payouts.release" onClick={() => setActing("release")}>
                  Release
                </ActionButton>
              </div>
            ) : null
          }
        >
          <Fields
            items={[
              [
                "State",
                run.state === "preview" ? <Pill>preview</Pill> : <Pill tone="running">released</Pill>,
              ],
              ["Payouts", run.payouts ?? 0],
              ["Total, net", <Money key="total" paise={run.totalPaise ?? 0} />],
              [
                "Released",
                run.releasedAt ? `by ${run.releasedBy ?? "—"}, ${formatDateTime(run.releasedAt)}` : "not yet",
              ],
            ]}
          />
          <ListBar list="payout-batch-items" tags={false} extra={{ batch: id }} />
          <PagedView
            query={items}
            what="the batch's hosts"
            items={(page) => page.items}
            empty={<Empty title="Nobody in this batch">No host has a checked UPI ID yet.</Empty>}
          >
            {(rows) => (
              <Table
                caption="The batch's hosts"
                list="payout-batch-items"
                sorts={{ Net: "net" }}
                columns={BATCH_COLUMNS}
              >
                {rows.map((item) => (
                  <Row key={item.userId}>
                    <Cell>
                      <Id value={item.userId} page="user" />
                    </Cell>
                    <Cell className="tabular">
                      <Money paise={item.grossPaise} />
                    </Cell>
                    <Cell className="tabular">
                      <Money paise={item.recoveredPaise} />
                    </Cell>
                    <Cell className="tabular">
                      <Money paise={item.heldPaise} />
                    </Cell>
                    <Cell className="tabular">
                      <Money paise={item.tdsPaise} />
                    </Cell>
                    <Cell className="tabular">
                      <Money paise={item.feePaise} />
                    </Cell>
                    <Cell className="tabular">
                      <Money paise={item.netPaise} />
                    </Cell>
                    <Cell>{item.upiMasked ?? "—"}</Cell>
                    <Cell>
                      {item.leftOutWords ? (
                        <span className="text-text-2">left out: {item.leftOutWords}</span>
                      ) : item.payoutStatus ? (
                        <Pill tone={STATUS_TONE[item.payoutStatus] ?? "plain"}>{item.payoutStatus}</Pill>
                      ) : (
                        <span className="text-text-2">when released</span>
                      )}
                    </Cell>
                  </Row>
                ))}
              </Table>
            )}
          </PagedView>
          <ActionSheet
            open={acting !== null}
            onOpenChange={(open) => !open && setActing(null)}
            record={run}
            title={acting === "release" ? "Release this batch?" : "Refresh the preview?"}
            change={
              acting === "release"
                ? "Another admin approves; then each host is paid as previewed, once. If anyone's figures changed since the preview, nobody is paid and the preview must be refreshed first."
                : "Each host's figures are taken again from now. Nothing is paid."
            }
            confirm={acting === "release" ? "Ask to release" : "Refresh"}
            onConfirm={async (reason) => {
              if (acting === "release") {
                await adminData(
                  adminApi.POST("/admin/v1/payouts/batches/{id}/release", {
                    params: { path: { id } },
                    body: { reason },
                  }),
                );
              } else {
                await adminData(
                  adminApi.POST("/admin/v1/payouts/batches/{id}/refresh", {
                    params: { path: { id } },
                    body: { reason },
                  }),
                );
              }
              await client.invalidateQueries({ queryKey: [ADMIN] });
            }}
          />
        </Section>
      )}
    </QueryView>
  );
}
