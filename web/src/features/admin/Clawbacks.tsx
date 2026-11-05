import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminClawback } from "./api";
import { AdminPage, Cell, Empty, Id, Pill, PagedView, Row, Section, SmallSelect, Table } from "./ui";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { useListParam, useListView } from "./listView";

/**
 * Clawbacks (ADR 0040): a report refunded after the session's earnings were paid out. What the host's balance covered,
 * what they owe and what was recovered since; Waive writes off what's still owed into chargeback losses, with a reason,
 * audited. Nothing is ever taken from a host's UPI ID.
 */
export function Clawbacks() {
  const client = useQueryClient();
  const [state, setState] = useListParam("state", "owed");
  const view = useListView();
  const [waiving, setWaiving] = useState<AdminClawback | null>(null);
  const clawbacks = useInfiniteQuery({
    queryKey: [ADMIN, "clawbacks", state, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/clawbacks", {
          params: { query: { state, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Clawbacks">
      <Section title="Refunds of earnings already paid out">
        <div className="flex">
          <SmallSelect
            label="Show"
            value={state}
            onChange={setState}
            options={[
              ["owed", "Still owed"],
              ["recovered", "Recovered"],
              ["waived", "Waived"],
              ["all", "All"],
            ]}
          />
        </div>
        <ListBar
          list="clawbacks"
          columns={[
            "When",
            "Host's owner",
            "Session",
            "Refunded",
            "From balance",
            "Owed",
            "Recovered",
            "",
            "",
          ]}
          filters={{ state: "state" }}
        />
        <PagedView
          query={clawbacks}
          what="the clawbacks"
          items={(page) => page.clawbacks}
          empty={
            <Empty title="Nothing here">
              A report refunded after its session was paid out is listed here.
            </Empty>
          }
        >
          {(rows) => (
            <Table
              caption="Clawbacks"
              sorts={{ When: "createdAt", Refunded: "amount" }}
              columns={[
                "When",
                "Host's owner",
                "Session",
                "Refunded",
                "From balance",
                "Owed",
                "Recovered",
                "",
                "",
              ]}
            >
              {rows.map((clawback) => (
                <Row key={clawback.id}>
                  <Cell>
                    <RelativeTime value={clawback.createdAt} />
                  </Cell>
                  <Cell>
                    <Id value={clawback.ownerId} page="user" />
                  </Cell>
                  <Cell>
                    <Id value={clawback.sessionId} page="session" />
                  </Cell>
                  <Cell>
                    <Money paise={clawback.amountPaise} />
                  </Cell>
                  <Cell>
                    <Money paise={clawback.fromBalancePaise} />
                  </Cell>
                  <Cell>
                    <Money paise={clawback.owedPaise} />
                  </Cell>
                  <Cell>
                    <Money paise={clawback.recoveredPaise} />
                  </Cell>
                  <Cell>
                    <Pill tone={clawback.state === "owed" ? "danger" : "plain"}>{clawback.state}</Pill>
                    {clawback.waiveReason && (
                      <span className="block text-caption text-text-2">
                        {clawback.waivedBy}: {clawback.waiveReason}
                      </span>
                    )}
                  </Cell>
                  <Cell>
                    {clawback.outstandingPaise > 0 && (
                      <ActionButton
                        permission="clawbacks.waive"
                        variant="quiet"
                        onClick={() => setWaiving(clawback)}
                      >
                        Waive<span className="sr-only"> {clawback.id}</span>
                      </ActionButton>
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      </Section>
      <ActionSheet
        open={waiving !== null}
        onOpenChange={(next) => !next && setWaiving(null)}
        record={waiving ?? undefined}
        title="Waive what's still owed?"
        change={
          waiving ? (
            <>
              The host no longer owes <Money paise={waiving.outstandingPaise} />: IdleO absorbs it into
              chargeback losses. It can&apos;t be undone.
            </>
          ) : null
        }
        confirm="Waive"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/clawbacks/{id}/waive", {
              params: { path: { id: waiving!.id } },
              body: { reason },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </AdminPage>
  );
}
