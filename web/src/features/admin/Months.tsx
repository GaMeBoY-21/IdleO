import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { StatusChip } from "@/components/ui/StatusChip";
import { RelativeTime } from "@/components/ui/Time";
import type { components } from "@/api/schema.gen";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { AdminPage, Cell, QueryView, Row, Section, Table } from "./ui";
import { ActionButton } from "./permissions";

type Period = components["schemas"]["AdminPeriod"];

const COLUMNS = ["Month", "State", "Checklist", "History", ""];

/** Whether the Owner may ask to close `period` now: it's over, open, and every item on its checklist holds. */
export function closable(period: Period): boolean {
  return period.over && period.state === "open" && period.checklist.every((item) => item.done);
}

/**
 * Closing months (ADR 0055), in India's time. Once a month is over and its checklist holds, the Owner asks to close
 * it; another admin approves, and from then the database refuses any entry dated in it. Reopening takes the Owner, an
 * approval and a reason, and pages everyone.
 */
export function Months() {
  const client = useQueryClient();
  const [acting, setActing] = useState<{ period: Period; action: "close" | "reopen" } | null>(null);
  const months = useQuery({
    queryKey: [ADMIN, "periods"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/periods", { params: { query: { months: 12 } } })),
  });
  return (
    <AdminPage title="Months">
      <Section title="The last twelve months">
        <QueryView query={months} what="the months">
          {(list) => (
            <Table caption="Months and their checklists" columns={COLUMNS}>
              {list.months.map((period) => (
                <Row key={period.month}>
                  <Cell className="tabular">{period.month}</Cell>
                  <Cell>
                    {period.state === "closed" ? (
                      <StatusChip size="sm" domain="month" state="closed" />
                    ) : period.over ? (
                      <StatusChip size="sm" domain="month" state="open" />
                    ) : (
                      <StatusChip size="sm" domain="month" state="current" />
                    )}
                  </Cell>
                  <Cell>
                    <ul className="flex flex-col gap-1 text-small">
                      {period.checklist.map((item) => (
                        <li key={item.name} className={item.done ? "" : "text-danger"}>
                          <span aria-hidden="true">{item.done ? "✓" : "✗"}</span> {item.name}
                          <span className="sr-only">{item.done ? ": done" : ": not yet"}</span>
                          <span className="block text-caption text-ink-subtle">{item.detail}</span>
                        </li>
                      ))}
                    </ul>
                  </Cell>
                  <Cell>
                    {period.events.length === 0 ? (
                      <span className="text-ink-subtle">—</span>
                    ) : (
                      <ul className="flex flex-col gap-1 text-small">
                        {period.events.map((event, index) => (
                          <li key={index}>
                            {event.action} by {event.actor} <RelativeTime value={event.at} />
                            <span className="block text-caption text-ink-subtle">{event.reason}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </Cell>
                  <Cell>
                    {period.state === "closed" ? (
                      <ActionButton
                        permission="periods.close"
                        variant="ghost"
                        onClick={() => setActing({ period, action: "reopen" })}
                      >
                        Reopen
                      </ActionButton>
                    ) : closable(period) ? (
                      <ActionButton
                        permission="periods.close"
                        onClick={() => setActing({ period, action: "close" })}
                      >
                        Close
                      </ActionButton>
                    ) : null}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </QueryView>
      </Section>
      <ActionSheet
        open={acting !== null}
        onOpenChange={(open) => !open && setActing(null)}
        record={acting?.period}
        destructive={acting?.action === "reopen"}
        title={
          acting?.action === "reopen"
            ? `Reopen ${acting.period.month}?`
            : `Close ${acting?.period.month ?? ""}?`
        }
        change={
          acting?.action === "reopen"
            ? "Entries can be dated in it again until it's closed once more. Another admin approves; it pages everyone, and the CA should get a corrected pack once it's closed again."
            : "From then the database refuses any ledger entry dated in it; a correction goes into an open month as an adjustment that names it. Another admin approves."
        }
        confirm={acting?.action === "reopen" ? "Ask to reopen" : "Ask to close"}
        onConfirm={async (reason) => {
          const month = acting!.period.month;
          if (acting!.action === "close") {
            await adminData(
              adminApi.POST("/admin/v1/periods/{month}/close", {
                params: { path: { month } },
                body: { reason },
              }),
            );
          } else {
            await adminData(
              adminApi.POST("/admin/v1/periods/{month}/reopen", {
                params: { path: { month } },
                body: { reason },
              }),
            );
          }
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </AdminPage>
  );
}
