import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminPaymentMismatch } from "./api";
import { downloadAdminInvoice } from "./Invoices";
import {
  AdminPage,
  Cell,
  Empty,
  Filters,
  Id,
  Pill,
  QueryView,
  Row,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

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
  return (
    <AdminPage title="Payments">
      <Search />
      <Reconciliation />
    </AdminPage>
  );
}

function Search() {
  const [typed, setTyped] = useState("");
  const [asked, setAsked] = useState("");
  const found = useQuery({
    queryKey: [ADMIN, "payments", asked],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payments", { params: { query: { query: asked } } })),
    enabled: asked !== "",
  });
  return (
    <Section title="Find a payment">
      <Filters onSubmit={() => setAsked(typed.trim())}>
        <SmallField
          label="Renter's id, top-up id, order or payment id"
          value={typed}
          onChange={setTyped}
          width="w-96"
        />
      </Filters>
      {asked === "" ? (
        <Empty title="Look a payment up">
          Give a renter&apos;s id, or Razorpay&apos;s order or payment id.
        </Empty>
      ) : (
        <QueryView
          query={found}
          what="the payments"
          isEmpty={(list) => list.payments.length === 0}
          empty={<Empty title="Nothing found">No top-up matches that.</Empty>}
        >
          {(list) => (
            <Table
              caption="Top-ups"
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
              {list.payments.map((payment) => (
                <Row key={payment.id}>
                  <Cell>
                    <RelativeTime value={payment.createdAt} />
                  </Cell>
                  <Cell>
                    <Id value={payment.userId} />
                  </Cell>
                  <Cell>
                    <Money paise={payment.amountPaise} />
                  </Cell>
                  <Cell>
                    <Pill
                      tone={
                        payment.status === "paid"
                          ? "running"
                          : payment.status === "failed"
                            ? "danger"
                            : "plain"
                      }
                    >
                      {payment.status}
                    </Pill>
                    {payment.failureReason && (
                      <span className="block text-caption text-text-muted">{payment.failureReason}</span>
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
                      <Button
                        variant="quiet"
                        onClick={() => void downloadAdminInvoice(payment.invoiceId!, payment.invoiceNumber!)}
                      >
                        {payment.invoiceNumber}
                      </Button>
                    ) : (
                      "—"
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </QueryView>
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
  const reconciliation = useQuery({
    queryKey: [ADMIN, "payment-reconciliation", state],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/payments/reconciliation", { params: { query: { state } } })),
  });
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="Razorpay against the ledger"
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
                columns={["Found", "Kind", "Razorpay's id", "Renter", "Ours", "Razorpay's", "Detail", ""]}
              >
                {found.mismatches.map((item) => (
                  <Row key={item.id}>
                    <Cell>
                      <RelativeTime value={item.lastFoundAt} />
                    </Cell>
                    <Cell>
                      <Pill tone={item.state === "open" ? "danger" : "plain"}>
                        {MISMATCH_WORDS[item.kind] ?? item.kind}
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
