import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise } from "@/lib/money";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminReviewMinute } from "./api";
import { AdminPage, Cell, Empty, Id, PagedView, Pill, Row, RowLink, SmallSelect, Table } from "./ui";

/** Why a minute is in the queue, in words. */
const WHY: Record<string, string> = {
  agent_only: "Only the agent reported it",
  lease_only: "Only the lease saw it",
  over_limit: "Past the cap or the credit",
  late_report: "Reported too late",
};

/**
 * The review queue (ADR 0017, ADR 0027): minutes only one witness saw, or that the cap or the credit couldn't pay for,
 * oldest first. Each is charged, as a settlement of its own within the cap and the credit, or waived; once.
 */
export function Review() {
  const client = useQueryClient();
  const [state, setState] = useState("open");
  const [deciding, setDeciding] = useState<{ minute: AdminReviewMinute; charge: boolean } | null>(null);
  const minutes = useInfiniteQuery({
    queryKey: [ADMIN, "review", state],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/review-minutes", {
          params: { query: { state, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Review queue">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["open", "Waiting for a decision"],
            ["decided", "Decided"],
            ["all", "All"],
          ]}
        />
      </div>
      <PagedView
        query={minutes}
        what="the review queue"
        items={(page) => page.minutes}
        empty={
          <Empty title={state === "open" ? "Nothing to review" : "No minutes here"}>
            {state === "open"
              ? "Every minute both witnesses saw has been charged. Minutes only one saw arrive here once their session closes."
              : "Decided minutes show here, with who decided and why."}
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Minutes in review"
            columns={["Session", "Minute", "Why", "Price", "Queued", "Decision", ""]}
          >
            {rows.map((minute) => (
              <Row key={`${minute.sessionId}:${minute.minuteIndex}`}>
                <Cell>
                  <RowLink href={`/admin/sessions/?id=${minute.sessionId}`}>
                    <Id value={minute.sessionId} />
                  </RowLink>
                </Cell>
                <Cell className="tabular">
                  <span title={formatDateTime(minute.minuteAt)}>
                    {minute.minuteIndex}
                    {minute.attempt === null ? "" : `, attempt ${minute.attempt}`}
                  </span>
                </Cell>
                <Cell>{WHY[minute.reason] ?? minute.reason}</Cell>
                <Cell className="tabular">
                  {minute.pricePaisePerMin == null ? "—" : formatPaise(minute.pricePaisePerMin)}
                </Cell>
                <Cell>
                  <RelativeTime value={minute.queuedAt} />
                </Cell>
                <Cell>
                  {minute.resolution ? (
                    <span className="flex flex-col gap-1">
                      <Pill tone={minute.resolution === "charged" ? "running" : "plain"}>
                        {minute.resolution}
                      </Pill>
                      <span className="text-caption text-text-muted">
                        {minute.resolutionReason}, {minute.resolvedBy}
                      </span>
                    </span>
                  ) : (
                    "—"
                  )}
                </Cell>
                <Cell>
                  {minute.resolution === null && (
                    <span className="flex gap-1">
                      <Button variant="secondary" onClick={() => setDeciding({ minute, charge: true })}>
                        Charge
                        <span className="sr-only">
                          {" "}
                          minute {minute.minuteIndex} of session {minute.sessionId}
                        </span>
                      </Button>
                      <Button variant="secondary" onClick={() => setDeciding({ minute, charge: false })}>
                        Waive
                        <span className="sr-only">
                          {" "}
                          minute {minute.minuteIndex} of session {minute.sessionId}
                        </span>
                      </Button>
                    </span>
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      {deciding !== null && (
        <ActionSheet
          open
          onOpenChange={(next) => !next && setDeciding(null)}
          title={
            deciding.charge
              ? `Charge minute ${deciding.minute.minuteIndex}?`
              : `Waive minute ${deciding.minute.minuteIndex}?`
          }
          change={
            deciding.charge
              ? `The renter is charged ${deciding.minute.pricePaisePerMin == null ? "its attempt's price" : formatPaise(deciding.minute.pricePaisePerMin)} for it, as a settlement of its own, and the host earns its share. Refused past the session's spending cap or the renter's credit.`
              : "It's never charged, and no money moves."
          }
          confirm={deciding.charge ? "Charge the minute" : "Waive the minute"}
          onConfirm={async (reason) => {
            const path = {
              params: {
                path: { sessionId: deciding.minute.sessionId, minuteIndex: deciding.minute.minuteIndex },
              },
              body: { reason },
            };
            await adminData(
              deciding.charge
                ? adminApi.POST("/admin/v1/review-minutes/{sessionId}/{minuteIndex}/charge", path)
                : adminApi.POST("/admin/v1/review-minutes/{sessionId}/{minuteIndex}/waive", path),
            );
            await client.invalidateQueries({ queryKey: [ADMIN] });
          }}
        />
      )}
    </AdminPage>
  );
}
