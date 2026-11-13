import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { FilterBar } from "@/components/ui/FilterBar";
import { useState } from "react";
import { StatusChip } from "@/components/ui/StatusChip";
import { useSearch } from "wouter";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminPaymentMismatch } from "./api";
import { downloadAdminInvoice } from "./Invoices";
import {
  AdminPage,
  Cell,
  Empty,
  Id,
  PagedView,
  QueryView,
  Row,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";
import { ActionButton } from "./permissions";
import { PaymentRecord } from "./Records";
import { ListBar } from "./Lists";
import { ListScope, useListParams, useListQuery, useListView } from "./listView";

const MISMATCH_COLUMNS = ["Found", "Kind", "Razorpay's id", "Renter", "Ours", "Razorpay's", "Detail", ""];

/** What each kind of disagreement means, for the admin who has to put it right (docs/runbooks/payments.md). */
export const MISMATCH_WORDS: Record<string, string> = {
  missing_ours: "Razorpay has it; we don't",
  missing_theirs: "We have it; Razorpay doesn't",
  amount: "A different amount",
  status: "A different state",
  captured_not_credited: "Captured, not credited",
  authorized_not_captured: "Authorised, not captured",
};

/**
 * Payments (ADR 0039): top-ups found by the renter's id, the top-up's, or Razorpay's order or payment id; and the
 * reconciliation of Razorpay against the ledger, its last runs and what it found, a day run again now, and an item
 * accepted with a reason once it's been looked into.
 */
export function Payments() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? (
    <AdminPage title="Payment" trail={[{ label: "Payments", href: "/admin/payments/" }]}>
      <PaymentRecord id={id} />
    </AdminPage>
  ) : (
    <PaymentSearch />
  );
}

function PaymentSearch() {
  return (
    <AdminPage title="Payments">
      <Search />
      <ListScope.Provider value="recon">
        <Reconciliation />
      </ListScope.Provider>
    </AdminPage>
  );
}

function Search() {
  const [{ query: asked }, setAsked] = useListParams({ query: "" });
  const [typed, setTyped] = useState(asked);
  const view = useListView();
  const found = useInfiniteQuery({
    queryKey: [ADMIN, "payments", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/payments", {
          params: {
            query: {
              ...(asked ? { query: asked } : {}),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: asked !== "" || view.tag !== undefined,
  });
  return (
    <Section title="Find a payment">
      <ListBar
        list="payments"
        extra={asked ? { [/^[0-9a-f-]{36}$/i.test(asked) ? "id" : "razorpay"]: asked } : {}}
      />
      <FilterBar onSubmit={() => setAsked({ query: typed.trim() })}>
        <SmallField
          label="Renter's id, top-up id, order or payment id"
          value={typed}
          onChange={setTyped}
          width="w-96"
        />
      </FilterBar>
      {asked === "" && view.tag === undefined ? (
        <Empty title="Look a payment up">
          Give a renter&apos;s id, or Razorpay&apos;s order or payment id.
        </Empty>
      ) : (
        <PagedView
          query={found}
          what="the payments"
          items={(page) => page.payments}
          empty={<Empty title="Nothing found">No top-up matches that.</Empty>}
        >
          {(rows) => (
            <Table
              caption="Top-ups"
              list="payments"
              sorts={{ Ordered: "createdAt", Amount: "amount", State: "status" }}
              columns={[
                "Ordered",
                "Renter",
                "Amount",
                "State",
                "Razorpay",
                "Method",
                "Credited by",
                "Document",
              ]}
            >
              {rows.map((payment) => (
                <Row key={payment.id}>
                  <Cell>
                    <RelativeTime value={payment.createdAt} />
                  </Cell>
                  <Cell>
                    <Id value={payment.userId} page="user" />
                  </Cell>
                  <Cell>
                    <Money paise={payment.amountPaise} />
                  </Cell>
                  <Cell>
                    <StatusChip size="sm" domain="payment" state={payment.status} />
                    {payment.failureReason && (
                      <span className="block text-caption text-ink-subtle">{payment.failureReason}</span>
                    )}
                  </Cell>
                  <Cell>
                    <span className="block tabular">{payment.orderId}</span>
                    {payment.paymentId && <span className="block tabular">{payment.paymentId}</span>}
                  </Cell>
                  <Cell>
                    {payment.method ?? "—"}
                    {payment.cardNetwork && ` ${payment.cardNetwork}`}
                    {payment.cardLast4 && ` ·${payment.cardLast4}`}
                  </Cell>
                  <Cell>{payment.creditedBy ?? "—"}</Cell>
                  <Cell>
                    {payment.invoiceId && payment.invoiceNumber ? (
                      <ActionButton
                        permission="invoices.pdf.read"
                        variant="quiet"
                        onClick={() => void downloadAdminInvoice(payment.invoiceId!, payment.invoiceNumber!)}
                      >
                        {payment.invoiceNumber}
                      </ActionButton>
                    ) : (
                      "—"
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      )}
    </Section>
  );
}

function Reconciliation() {
  const client = useQueryClient();
  const [state, setState] = useState("open");
  const [running, setRunning] = useState(false);
  const [day, setDay] = useState("");
  const [accepting, setAccepting] = useState<AdminPaymentMismatch | null>(null);
  const view = useListView();
  const reconciliation = useListQuery(
    [ADMIN, "payment-reconciliation", state, view],
    "mismatches",
    (cursor?: string) =>
      adminData(
        adminApi.GET("/admin/v1/payments/reconciliation", {
          params: { query: { state, ...view, ...(cursor ? { cursor } : {}) } },
        }),
      ),
  );
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="Razorpay against the ledger"
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
      <ListBar list="payment-mismatches" tags={false} extra={state === "all" ? {} : { state }} />
      <QueryView query={reconciliation} what="the reconciliation">
        {(found) => (
          <>
            {found.mismatches.length === 0 ? (
              <Empty title={state === "open" ? "Nothing open" : "Nothing here"}>
                Each night at 03:15 in India, yesterday&apos;s payments and refunds are compared with the
                ledger; anything that disagrees is listed here.
              </Empty>
            ) : (
              <Table
                caption="What disagrees"
                list="payment-mismatches"
                sorts={{ Found: "lastFoundAt", Kind: "kind" }}
                columns={MISMATCH_COLUMNS}
              >
                {found.mismatches.map((item) => (
                  <Row key={item.id}>
                    <Cell>
                      <RelativeTime value={item.lastFoundAt} />
                    </Cell>
                    <Cell>
                      <StatusChip
                        size="sm"
                        domain="mismatch"
                        state={item.state}
                        label={MISMATCH_WORDS[item.kind] ?? item.kind}
                      />
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
                        <span className="block text-caption text-ink-subtle">
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
            <Table
              caption="Runs"
              columns={["Day", "Asked by", "Payments", "Refunds", "Found", "Open after", "Finished"]}
            >
              {found.runs.map((run) => (
                <Row key={run.id}>
                  <Cell className="tabular">{run.day}</Cell>
                  <Cell>{run.trigger}</Cell>
                  <Cell className="tabular">{run.payments ?? "—"}</Cell>
                  <Cell className="tabular">{run.refunds ?? "—"}</Cell>
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
        title="Compare a day with Razorpay now?"
        change="It lists that day's payments and refunds from Razorpay and compares each with the ledger, as the nightly run does. A captured payment nobody credited is credited by the same check as every payment; nothing else moves money. Running a day again adds nothing it found before."
        confirm="Run it"
        extra={
          <SmallField label="Day in India (YYYY-MM-DD), empty for today" value={day} onChange={setDay} />
        }
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/payments/reconciliation", {
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
              {MISMATCH_WORDS[accepting.kind] ?? accepting.kind} on {accepting.subject}: {accepting.detail} It
              closes the item; a later run that finds it again leaves it accepted. Say what you did about it.
            </>
          ) : null
        }
        confirm="Accept"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/payments/mismatches/{id}/accept", {
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
