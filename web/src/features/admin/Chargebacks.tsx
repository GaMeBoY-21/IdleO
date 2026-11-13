import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { KeyValueList } from "@/components/ui/KeyValues";
import { StatusChip } from "@/components/ui/StatusChip";
import { useSearch } from "wouter";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ADMIN, adminApi, adminData, type AdminChargeback } from "./api";
import { RECORDS } from "./recordQueries";
import { word } from "./words";
import { SessionLog } from "./SessionLog";
import {
  AdminPage,
  Cell,
  Empty,
  PagedView,
  Id,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallSelect,
  Table,
} from "./ui";
import { ListBar } from "./Lists";
import { useListParam, useListView } from "./listView";

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
    return <StatusChip size="sm" domain="chargeback" state={chargeback.status} />;
  }
  return <StatusChip size="sm" domain="chargeback" state={chargeback.outcome} />;
}

function ChargebackList() {
  const [state, setState] = useListParam("state", "open");
  const view = useListView();
  // Read once, so "due soon" doesn't change under a reader.
  const [now] = useState(() => Date.now());
  const chargebacks = useInfiniteQuery({
    queryKey: [ADMIN, "chargebacks", state, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/chargebacks", {
          params: { query: { state, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
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
      <ListBar list="chargebacks" />
      <PagedView
        query={chargebacks}
        what="the chargebacks"
        items={(page) => page.chargebacks}
        empty={
          <Empty title="No chargebacks">When a renter&apos;s bank disputes a payment, it arrives here.</Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Chargebacks"
            list="chargebacks"
            sorts={{ Opened: "receivedAt", Disputed: "amount" }}
            columns={["Evidence due", "Opened", "Renter", "Disputed", "Frozen", "Reason", "State", ""]}
          >
            {rows.map((chargeback) => (
              <Row key={chargeback.id}>
                <Cell className={dueSoon(chargeback, now) ? "font-semibold text-danger tabular" : "tabular"}>
                  {chargeback.respondBy ? formatDateTime(chargeback.respondBy) : "—"}
                </Cell>
                <Cell>
                  <RelativeTime value={chargeback.openedAt} />
                </Cell>
                <Cell>
                  {chargeback.userId ? <Id value={chargeback.userId} page="user" /> : "not one of ours"}
                </Cell>
                <Cell>
                  <Money paise={chargeback.amountPaise} />
                </Cell>
                <Cell>
                  <Money paise={chargeback.frozenPaise} />
                </Cell>
                <Cell>{chargeback.reasonCode ? word(chargeback.reasonCode) : "—"}</Cell>
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
      </PagedView>
    </AdminPage>
  );
}

function ChargebackRecord({ id }: { id: string }) {
  const chargeback = useQuery(RECORDS.chargeback(id));
  return (
    <AdminPage
      title="Chargeback"
      name={`…${id.slice(-8)}`}
      trail={[{ label: "Chargebacks", href: "/admin/chargebacks/" }]}
    >
      <QueryView query={chargeback} what="this chargeback">
        {(found) => (
          <>
            <KeyValueList
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
                [
                  "Reason",
                  `${found.reasonCode ? word(found.reasonCode) : "—"}${found.phase ? ` (${found.phase})` : ""}`,
                ],
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
                    <Id key="renter" value={found.userId} page="user" />
                  ) : (
                    "not one of our top-ups"
                  ),
                ],
              ]}
            />
            <p className="max-w-prose text-small text-ink-subtle">
              Submit the evidence in Razorpay&apos;s dashboard, Disputes, before it&apos;s due: the payment,
              the sessions it paid for, and their minutes and logs below. A lost dispute takes the frozen
              credit and suspends the account until someone reviews it.
            </p>
            <Section title="The payment">
              {found.payment ? (
                <KeyValueList
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
                        <Id value={session.sessionId} page="session" />
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
