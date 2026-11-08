import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ActionSheet, shownValue } from "./ActionSheet";
import { ADMIN, adminApi, adminData, adminFile, type AdminApproval } from "./api";
import { ActionButton } from "./permissions";
import { useMe } from "@/api/queries";
import { ListBar } from "./Lists";
import { useListView } from "./listView";
import { AdminPage, Cell, Empty, PagedView, Pill, Row, Section, SmallSelect, Table, Problem } from "./ui";

/** A change, as the approver reads it. */
function Changes({ request }: { request: AdminApproval }) {
  return (
    <ul className="flex flex-col gap-1 text-caption">
      {request.changes.map((change) => (
        <li key={change.what}>
          <span className="font-medium">{change.what}</span>: {shownValue(change.from)} →{" "}
          {shownValue(change.to)}
        </li>
      ))}
    </ul>
  );
}

const COLUMNS = ["Asked", "What", "Will change", "Why", "State", ""];

type Decision = { request: AdminApproval; kind: "approve" | "reject" | "cancel" };

const TITLES = { approve: "Approve this?", reject: "Reject this?", cancel: "Cancel this?" };
const CONFIRM = { approve: "Approve and run it", reject: "Reject", cancel: "Cancel it" };

/**
 * Approvals (ADR 0051): what's waiting, what this admin asked for, what's decided. Each shows what will change. Another
 * admin with the action's permission decides; whoever asked can't approve their own. A request with no one else to ask
 * waits out a delay instead, and runs unless it's cancelled.
 */
export function Approvals() {
  const client = useQueryClient();
  const [which, setWhich] = useState("pending");
  const [deciding, setDeciding] = useState<Decision | null>(null);
  const [error, setError] = useState<unknown>(null);
  const view = useListView();
  const me = useMe();
  const requests = useInfiniteQuery({
    queryKey: [ADMIN, "approvals", which, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/approvals", {
          params: { query: { which, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const download = async (request: AdminApproval) => {
    setError(null);
    try {
      await adminFile(
        adminApi.POST("/admin/v1/approvals/{id}/download", {
          params: { path: { id: request.id } },
          parseAs: "blob",
        }),
        `idleo-${request.kind}-${request.id.slice(0, 8)}.csv`,
      );
      await client.invalidateQueries({ queryKey: [ADMIN, "approvals"] });
    } catch (failed) {
      setError(failed);
    }
  };
  return (
    <AdminPage title="Approvals">
      <Section
        title="Requests"
        action={
          <SmallSelect
            label="Show"
            value={which}
            onChange={setWhich}
            options={[
              ["pending", "Waiting"],
              ["mine", "Mine"],
              ["decided", "Decided"],
            ]}
          />
        }
      >
        <Problem error={error} />
        <ListBar
          list="approvals"
          tags={false}
          extra={which === "mine" ? (me.data ? { requestedBy: me.data.id } : {}) : { which }}
        />
        <PagedView
          query={requests}
          what="the approvals"
          items={(page) => page.requests}
          empty={<Empty title="Nothing here">Actions that need a second admin wait here.</Empty>}
        >
          {(rows) => (
            <Table
              caption="Approval requests"
              list="approvals"
              sorts={{ Asked: "createdAt", What: "kind", State: "state" }}
              columns={COLUMNS}
            >
              {rows.map((request) => (
                <Row key={request.id}>
                  <Cell>
                    <RelativeTime value={request.createdAt} />
                    <span className="tabular block text-caption text-text-2 break-all">
                      {request.requestedBy}
                    </span>
                  </Cell>
                  <Cell className="font-medium">{request.kind}</Cell>
                  <Cell className="max-w-64">
                    <Changes request={request} />
                  </Cell>
                  <Cell className="max-w-64">{request.reason}</Cell>
                  <Cell>
                    <Pill tone={request.state === "failed" ? "danger" : "plain"}>{request.state}</Pill>
                    {request.state === "pending" && request.solo && request.runAfter && (
                      <span className="block text-caption text-text-2">
                        Runs by itself at {formatDateTime(request.runAfter)}
                      </span>
                    )}
                    {request.failure && (
                      <span className="block text-caption text-text-2">{request.failure}</span>
                    )}
                    {request.decisionReason && (
                      <span className="block text-caption text-text-2">{request.decisionReason}</span>
                    )}
                  </Cell>
                  <Cell>
                    {request.state === "pending" && (
                      <div className="flex flex-wrap gap-2">
                        <ActionButton
                          permission={request.permission}
                          variant="ghost"
                          onClick={() => setDeciding({ request, kind: "approve" })}
                        >
                          Approve
                        </ActionButton>
                        <ActionButton
                          permission={request.permission}
                          variant="quiet"
                          onClick={() => setDeciding({ request, kind: "reject" })}
                        >
                          Reject
                        </ActionButton>
                        <ActionButton
                          permission="approvals.decide"
                          variant="quiet"
                          onClick={() => setDeciding({ request, kind: "cancel" })}
                        >
                          Cancel
                        </ActionButton>
                      </div>
                    )}
                    {request.downloadable && (
                      <Button variant="ghost" onClick={() => void download(request)}>
                        Fetch the file, once
                      </Button>
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      </Section>
      <ActionSheet
        open={deciding !== null}
        onOpenChange={(next) => !next && setDeciding(null)}
        title={deciding ? TITLES[deciding.kind] : ""}
        change={
          deciding ? (
            <div className="flex flex-col gap-2">
              <span>
                {deciding.request.kind}, asked because: {deciding.request.reason}
              </span>
              <Changes request={deciding.request} />
              {deciding.kind === "approve" && <span>It runs now, as it was asked.</span>}
            </div>
          ) : null
        }
        confirm={deciding ? CONFIRM[deciding.kind] : ""}
        destructive={deciding?.kind !== "approve"}
        record={deciding?.request}
        onConfirm={async (reason) => {
          if (!deciding) {
            return;
          }
          const path = {
            approve: "/admin/v1/approvals/{id}/approve",
            reject: "/admin/v1/approvals/{id}/reject",
            cancel: "/admin/v1/approvals/{id}/cancel",
          } as const;
          await adminData(
            adminApi.POST(path[deciding.kind], {
              params: { path: { id: deciding.request.id } },
              body: { reason },
            }),
          );
        }}
      />
    </AdminPage>
  );
}
