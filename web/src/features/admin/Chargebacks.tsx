import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ADMIN, adminApi, adminData, type AdminChargeback } from "./api";
import { SessionLog } from "./SessionLog";
import {
  AdminPage,
  BackLink,
  Cell,
  Empty,
  Fields,
  Id,
  Pill,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallSelect,
  Table,
} from "./ui";

/** Whether a chargeback's evidence is due within two days, or already late. */
const dueSoon = (chargeback: AdminChargeback, now: number) =>
  chargeback.outcome == null &&
  chargeback.respondBy != null &&
  new Date(chargeback.respondBy).getTime() - now < 2 * 24 * 60 * 60 * 1000;

/**
 * Chargebacks (ADR 0039): a renter's bank disputing one of our payments. Open ones by the date evidence is due in
 * Razorpay's dashboard (it's submitted there, never from here: docs/runbooks/payments.md); each with the payment, the
 * sessions its credit paid for (each opens with its metering and log), what's frozen, and how it ended.
 */
export function Chargebacks() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <ChargebackRecord id={id} /> : <ChargebackList />;
}

function OutcomePill({ chargeback }: { chargeback: AdminChargeback }) {
  if (chargeback.outcome == null) {
    return <Pill tone="danger">{chargeback.status.replaceAll("_", " ")}</Pill>;
  }
  return <Pill tone={chargeback.outcome === "won" ? "running" : "plain"}>{chargeback.outcome}</Pill>;
}

function ChargebackList() {
  const [state, setState] = useState("open");
  // Read once, so "due soon" doesn't change under a reader.
  const [now] = useState(() => Date.now());
  const chargebacks = useQuery({
    queryKey: [ADMIN, "chargebacks", state],
    queryFn: () => adminData(adminApi.GET("/admin/v1/chargebacks", { params: { query: { state } } })),
  });
  return (
    <AdminPage title="Chargebacks">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["open", "Open, by the date evidence is due"],
            ["all", "All, the newest first"],
          ]}
        />
      </div>
      <QueryView
        query={chargebacks}
        what="the chargebacks"
        isEmpty={(list) => list.chargebacks.length === 0}
        empty={
          <Empty title="No chargebacks">When a renter&apos;s bank disputes a payment, it arrives here.</Empty>
        }
      >
        {(list) => (
          <Table
            caption="Chargebacks"
            columns={["Evidence due", "Opened", "Renter", "Disputed", "Frozen", "Reason", "State", ""]}
          >
            {list.chargebacks.map((chargeback) => (
              <Row key={chargeback.id}>
                <Cell className={dueSoon(chargeback, now) ? "font-semibold text-danger tabular" : "tabular"}>
                  {chargeback.respondBy ? formatDateTime(chargeback.respondBy) : "—"}
                </Cell>
                <Cell>
                  <RelativeTime value={chargeback.openedAt} />
                </Cell>
                <Cell>{chargeback.userId ? <Id value={chargeback.userId} /> : "not one of ours"}</Cell>
                <Cell>
                  <Money paise={chargeback.amountPaise} />
                </Cell>
                <Cell>
                  <Money paise={chargeback.frozenPaise} />
                </Cell>
                <Cell>{chargeback.reasonCode ?? "—"}</Cell>
                <Cell>
                  <OutcomePill chargeback={chargeback} />
                </Cell>
                <Cell>
                  <RowLink href={`/admin/chargebacks/?id=${chargeback.id}`}>
                    Open<span className="sr-only"> chargeback {chargeback.disputeId}</span>
                  </RowLink>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
    </AdminPage>
  );
}

function ChargebackRecord({ id }: { id: string }) {
  const chargeback = useQuery({
    queryKey: [ADMIN, "chargeback", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/chargebacks/{id}", { params: { path: { id } } })),
  });
  return (
    <AdminPage title="Chargeback">
      <BackLink href="/admin/chargebacks/">← All chargebacks</BackLink>
      <QueryView query={chargeback} what="this chargeback">
        {(found) => (
          <>
            <Fields
              items={[
                ["State", <OutcomePill key="state" chargeback={found} />],
                [
                  "Razorpay's dispute",
                  <span key="dispute" className="tabular">
                    {found.disputeId}
                  </span>,
                ],
                [
                  "Evidence due",
                  <span key="due" className="tabular">
                    {found.respondBy ? formatDateTime(found.respondBy) : "—"}
                  </span>,
                ],
                ["Disputed", <Money key="amount" paise={found.amountPaise} />],
                ["Reason", `${found.reasonCode ?? "—"}${found.phase ? ` (${found.phase})` : ""}`],
                ["Frozen now", <Money key="frozen" paise={found.frozenPaise} />],
                ["Deducted by Razorpay", <Money key="deducted" paise={found.deductedPaise} />],
                ...(found.outcome != null
                  ? ([
                      ["Lost", <Money key="lost" paise={found.lostPaise ?? 0} />],
                      ["Given back to the renter", <Money key="released" paise={found.releasedPaise ?? 0} />],
                    ] as [string, React.ReactNode][])
                  : []),
                [
                  "Renter",
                  found.userId ? (
                    <Link
                      key="renter"
                      href={`/admin/users/?id=${found.userId}`}
                      className="tabular underline-offset-4 hover:underline"
                    >
                      {found.userId}
                    </Link>
                  ) : (
                    "not one of our top-ups"
                  ),
                ],
              ]}
            />
            <p className="max-w-prose text-small text-text-muted">
              Submit the evidence in Razorpay&apos;s dashboard, Disputes, before it&apos;s due: the payment,
              the sessions it paid for, and their minutes and logs below. A lost dispute takes the frozen
              credit and suspends the account until someone reviews it.
            </p>
            <Section title="The payment">
              {found.payment ? (
                <Fields
                  items={[
                    ["Paid", <Money key="paid" paise={found.payment.amountPaise} />],
                    [
                      "When",
                      <span key="when" className="tabular">
                        {found.payment.paidAt ? formatDateTime(found.payment.paidAt) : "—"}
                      </span>,
                    ],
                    ["Razorpay's ids", `${found.payment.orderId} · ${found.payment.paymentId ?? "—"}`],
                    [
                      "Method",
                      `${found.payment.method ?? "—"}${found.payment.cardNetwork ? ` ${found.payment.cardNetwork}` : ""}${found.payment.cardLast4 ? ` ending ${found.payment.cardLast4}` : ""}`,
                    ],
                    ["Document", found.payment.invoiceNumber ?? "—"],
                  ]}
                />
              ) : (
                <Empty title="Not one of our top-ups">
                  Razorpay&apos;s payment {found.paymentId} isn&apos;t a top-up we know; the reconciliation
                  lists it.
                </Empty>
              )}
            </Section>
            <Section title="Sessions it paid for">
              {found.sessions.length === 0 ? (
                <Empty title="None">None of this payment&apos;s credit was spent on a session.</Empty>
              ) : (
                <Table caption="Sessions paid from this payment" columns={["Session", "Paid from it", ""]}>
                  {found.sessions.map((session) => (
                    <Row key={session.sessionId}>
                      <Cell>
                        <Id value={session.sessionId} />
                      </Cell>
                      <Cell>
                        <Money paise={session.paidFromThisPaymentPaise} />
                      </Cell>
                      <Cell>
                        <RowLink href={`/admin/sessions/?id=${session.sessionId}`}>
                          Its timeline and minutes<span className="sr-only"> for {session.sessionId}</span>
                        </RowLink>
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )}
            </Section>
            {found.sessions.map((session) => (
              <SessionLog
                key={session.sessionId}
                sessionId={session.sessionId}
                purpose={`evidence for chargeback ${found.disputeId}`}
              />
            ))}
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}
