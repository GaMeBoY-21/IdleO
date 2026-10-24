import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminPanReview, type AdminUpiReview } from "./api";
import { AdminPage, Cell, Empty, Id, Pill, QueryView, Row, Section, Table } from "./ui";

type PanAction = { kind: "verify-pan" | "reject-pan" | "reveal-pan"; pan: AdminPanReview };
type UpiAction = { kind: "approve-upi" | "reject-upi"; upi: AdminUpiReview };
type Action = PanAction | UpiAction;

const isPan = (action: Action): action is PanAction => "pan" in action;

/**
 * Verification (ADR 0040): PANs waiting for an admin (no provider checks them online yet, or the name didn't match) and
 * UPI IDs whose bank name matched the PAN's below the threshold. Verify or reject each with a reason, audited; read a
 * PAN whole only with a reason, also audited. The whole PAN is shown in this sheet and held nowhere else.
 */
export function Verification() {
  const client = useQueryClient();
  const [action, setAction] = useState<Action | null>(null);
  const [revealed, setRevealed] = useState<{ userId: string; pan: string } | null>(null);
  const reviews = useQuery({
    queryKey: [ADMIN, "verifications"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/payouts/verifications")),
  });
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <AdminPage title="Verification">
      <QueryView query={reviews} what="the reviews">
        {(found) => (
          <>
            <Section title="PANs">
              {found.pans.length === 0 ? (
                <Empty title="No PAN to check">
                  PANs hosts and renters give wait here for you to confirm.
                </Empty>
              ) : (
                <Table
                  caption="PANs waiting"
                  columns={["Given", "Person", "PAN", "Name on it", "Holder", "Check", ""]}
                >
                  {found.pans.map((pan) => (
                    <Row key={pan.userId}>
                      <Cell>
                        <RelativeTime value={pan.submittedAt} />
                      </Cell>
                      <Cell>
                        <Id value={pan.userId} />
                      </Cell>
                      <Cell className="tabular">
                        {revealed?.userId === pan.userId ? revealed.pan : `••••••${pan.last4}`}
                      </Cell>
                      <Cell>{pan.name}</Cell>
                      <Cell>{pan.holder}</Cell>
                      <Cell>
                        <Pill tone={pan.checked === "name_mismatch" ? "danger" : "plain"}>
                          {pan.checked === "not_checked" ? "no provider yet" : pan.checked.replace("_", " ")}
                        </Pill>
                      </Cell>
                      <Cell>
                        <div className="flex gap-1">
                          <Button variant="quiet" onClick={() => setAction({ kind: "verify-pan", pan })}>
                            Verify<span className="sr-only"> {pan.userId}</span>
                          </Button>
                          <Button variant="quiet" onClick={() => setAction({ kind: "reject-pan", pan })}>
                            Reject<span className="sr-only"> {pan.userId}</span>
                          </Button>
                          <Button variant="quiet" onClick={() => setAction({ kind: "reveal-pan", pan })}>
                            Reveal<span className="sr-only"> {pan.userId}</span>
                          </Button>
                        </div>
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )}
            </Section>
            <Section title="UPI IDs">
              {found.upiIds.length === 0 ? (
                <Empty title="No UPI ID to check">
                  A UPI ID whose bank name matches the PAN&apos;s below 80 waits here.
                </Empty>
              ) : (
                <Table
                  caption="UPI IDs waiting"
                  columns={["Confirmed", "Person", "UPI ID", "Bank's name", "PAN's name", "Match", ""]}
                >
                  {found.upiIds.map((upi) => (
                    <Row key={upi.id}>
                      <Cell>{upi.confirmedAt ? <RelativeTime value={upi.confirmedAt} /> : "—"}</Cell>
                      <Cell>
                        <Id value={upi.userId} />
                      </Cell>
                      <Cell className="break-all">{upi.vpa ?? "—"}</Cell>
                      <Cell>{upi.registeredName ?? "—"}</Cell>
                      <Cell>{upi.panName ?? "—"}</Cell>
                      <Cell className="tabular">{upi.nameMatchScore ?? "—"}</Cell>
                      <Cell>
                        <div className="flex gap-1">
                          <Button variant="quiet" onClick={() => setAction({ kind: "approve-upi", upi })}>
                            Approve<span className="sr-only"> {upi.vpa}</span>
                          </Button>
                          <Button variant="quiet" onClick={() => setAction({ kind: "reject-upi", upi })}>
                            Reject<span className="sr-only"> {upi.vpa}</span>
                          </Button>
                        </div>
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )}
            </Section>
          </>
        )}
      </QueryView>
      <ActionSheet
        open={action !== null}
        onOpenChange={(next) => !next && setAction(null)}
        title={action ? TITLES[action.kind] : ""}
        change={action ? change(action) : null}
        confirm={action ? CONFIRM[action.kind] : ""}
        onConfirm={async (reason) => {
          if (!action) {
            return;
          }
          if (action.kind === "reveal-pan") {
            const shown = await adminData(
              adminApi.POST("/admin/v1/payouts/verifications/pans/{userId}/reveal", {
                params: { path: { userId: action.pan.userId } },
                body: { reason },
              }),
            );
            setRevealed(shown);
            return;
          }
          if (isPan(action)) {
            await adminData(
              adminApi.POST(
                action.kind === "verify-pan"
                  ? "/admin/v1/payouts/verifications/pans/{userId}/verify"
                  : "/admin/v1/payouts/verifications/pans/{userId}/reject",
                { params: { path: { userId: action.pan.userId } }, body: { reason } },
              ),
            );
          } else {
            await adminData(
              adminApi.POST(
                action.kind === "approve-upi"
                  ? "/admin/v1/payouts/verifications/upi-ids/{id}/approve"
                  : "/admin/v1/payouts/verifications/upi-ids/{id}/reject",
                { params: { path: { id: action.upi.id } }, body: { reason } },
              ),
            );
          }
          await refresh();
        }}
      />
    </AdminPage>
  );
}

const TITLES: Record<Action["kind"], string> = {
  "verify-pan": "Verify this PAN?",
  "reject-pan": "Reject this PAN?",
  "reveal-pan": "Read this PAN whole?",
  "approve-upi": "Approve this UPI ID?",
  "reject-upi": "Reject this UPI ID?",
};

const CONFIRM: Record<Action["kind"], string> = {
  "verify-pan": "Verify",
  "reject-pan": "Reject",
  "reveal-pan": "Show it",
  "approve-upi": "Approve",
  "reject-upi": "Reject",
};

function change(action: Action) {
  switch (action.kind) {
    case "verify-pan":
      return `The PAN ending ${action.pan.last4}, in the name ${action.pan.name}, is verified: they can be paid, and buy past the yearly amount.`;
    case "reject-pan":
      return "They're asked to give it again. Your reason is what they read.";
    case "reveal-pan":
      return "The whole PAN is shown here once, and the audit log records that you read it and why.";
    case "approve-upi":
      return `Payouts go to ${action.upi.vpa ?? "it"}, registered to ${action.upi.registeredName ?? "an unknown name"}, against the PAN's ${action.upi.panName ?? "name"}.`;
    case "reject-upi":
      return "Payouts don't go to it; they're asked to check another UPI ID.";
  }
}
