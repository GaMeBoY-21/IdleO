import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise, parseRupees, rupeeInput } from "@/lib/money";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminCreditRefund } from "./api";
import { refundProblem } from "./rules";
import { AdminPage, Cell, Empty, Id, Pill, QueryView, Row, SmallField, SmallSelect, Table } from "./ui";

/**
 * Refunds of bought credit (ADR 0039): requests waiting for an answer, oldest first, each with what the renter can have
 * back now; approve an amount (at most that), which reserves the credit at once and refunds it to the payments it came
 * from, newest first, or decline with a reason the renter reads.
 */
export function Refunds() {
  const client = useQueryClient();
  const [status, setStatus] = useState("requested");
  const [acting, setActing] = useState<{ refund: AdminCreditRefund; approve: boolean } | null>(null);
  const [rupees, setRupees] = useState("");
  const refunds = useQuery({
    queryKey: [ADMIN, "refunds", status],
    queryFn: () => adminData(adminApi.GET("/admin/v1/refunds", { params: { query: { status } } })),
  });
  const close = () => {
    setActing(null);
    setRupees("");
  };
  return (
    <AdminPage title="Refunds">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={status}
          onChange={setStatus}
          options={[
            ["requested", "Waiting for an answer"],
            ["refunding", "On their way"],
            ["refunded", "Refunded"],
            ["failed", "Failed"],
            ["declined", "Declined"],
            ["all", "All"],
          ]}
        />
      </div>
      <QueryView
        query={refunds}
        what="the refunds"
        isEmpty={(list) => list.refunds.length === 0}
        empty={
          <Empty title="Nothing here">
            A renter asks from Credit, Refund unused credit; it arrives here and as a money message in
            Tickets.
          </Empty>
        }
      >
        {(list) => (
          <Table
            caption="Refunds of bought credit"
            columns={["Asked", "Renter", "Why", "Asked for", "Can have back", "State", "Parts", ""]}
          >
            {list.refunds.map((refund) => (
              <Row key={refund.id}>
                <Cell>
                  <RelativeTime value={refund.requestedAt} />
                  {refund.ticketNumber != null && (
                    <span className="block text-caption text-text-muted">message #{refund.ticketNumber}</span>
                  )}
                </Cell>
                <Cell>
                  <Id value={refund.userId} />
                </Cell>
                <Cell>{refund.cause === "deletion" ? "Account deletion" : (refund.note ?? "—")}</Cell>
                <Cell>
                  {refund.requestedPaise != null ? <Money paise={refund.requestedPaise} /> : "All of it"}
                </Cell>
                <Cell>
                  <Money paise={refund.refundablePaise} />
                </Cell>
                <Cell>
                  <Pill
                    tone={
                      refund.status === "failed"
                        ? "danger"
                        : refund.status === "refunded"
                          ? "running"
                          : "plain"
                    }
                  >
                    {refund.status}
                  </Pill>
                  {refund.decision && (
                    <span className="block text-caption text-text-muted">{refund.decision}</span>
                  )}
                </Cell>
                <Cell>
                  {refund.parts.length === 0 ? (
                    "—"
                  ) : (
                    <ul className="flex flex-col gap-1 text-caption">
                      {refund.parts.map((part) => (
                        <li key={part.id}>
                          <Money paise={part.amountPaise} /> to{" "}
                          <span className="tabular">{part.paymentId}</span>: {part.status}
                          {part.failureReason ? ` (${part.failureReason})` : ""}
                        </li>
                      ))}
                    </ul>
                  )}
                </Cell>
                <Cell>
                  {refund.status === "requested" && (
                    <div className="flex gap-1">
                      <Button
                        variant="quiet"
                        onClick={() => {
                          setRupees(
                            rupeeInput(
                              Math.min(
                                refund.requestedPaise ?? refund.refundablePaise,
                                refund.refundablePaise,
                              ),
                            ),
                          );
                          setActing({ refund, approve: true });
                        }}
                      >
                        Approve<span className="sr-only"> refund for {refund.userId}</span>
                      </Button>
                      <Button variant="quiet" onClick={() => setActing({ refund, approve: false })}>
                        Decline<span className="sr-only"> refund for {refund.userId}</span>
                      </Button>
                    </div>
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      <ActionSheet
        open={acting?.approve === true}
        onOpenChange={(next) => !next && close()}
        title="Refund this bought credit?"
        change={
          acting ? (
            <>
              The renter can have back at most {formatPaise(acting.refund.refundablePaise)} now: bought credit
              not held for a session. It leaves their credit at once and goes back to the payments it came
              from, the newest first, never anywhere else; it comes back if Razorpay can&apos;t refund it.
            </>
          ) : null
        }
        confirm="Refund"
        extra={<SmallField label="Amount in ₹" value={rupees} onChange={setRupees} inputMode="decimal" />}
        onConfirm={async (reason) => {
          const paise = parseRupees(rupees);
          const problem = refundProblem(
            paise,
            acting!.refund.refundablePaise,
            "the bought credit they can have back now",
          );
          if (problem !== null || paise === null) {
            throw new SheetProblem(problem ?? "Give an amount.");
          }
          await adminData(
            adminApi.POST("/admin/v1/refunds/{id}/approve", {
              params: { path: { id: acting!.refund.id } },
              body: { amountPaise: paise, reason },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
      <ActionSheet
        open={acting?.approve === false}
        onOpenChange={(next) => !next && close()}
        title="Decline this refund?"
        change="No money moves; the credit stays with the renter. They read your reason."
        confirm="Decline"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/refunds/{id}/decline", {
              params: { path: { id: acting!.refund.id } },
              body: { reason },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </AdminPage>
  );
}
