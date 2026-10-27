import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData } from "./api";
import { AdminPage, Cell, Empty, Filters, PagedView, Row, SmallField, Table } from "./ui";

/** A change, as its JSON, short enough for a row. */
function change(value: Record<string, unknown> | null | undefined): string {
  return value ? JSON.stringify(value) : "—";
}

/**
 * The audit log (ADR 0027): every privileged action, newest first, read only. Filtered by who (part of a name, or a
 * user id), what (an action, or the start of one: `host.`), and the target.
 */
export function Audit() {
  const [form, setForm] = useState({ actor: "", action: "", targetId: "" });
  const [asked, setAsked] = useState(form);
  const set = (field: keyof typeof form) => (value: string) => setForm({ ...form, [field]: value });
  const entries = useInfiniteQuery({
    queryKey: [ADMIN, "audit", asked],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/audit", {
          params: {
            query: {
              ...(asked.actor ? { actor: asked.actor } : {}),
              ...(asked.action ? { action: asked.action } : {}),
              ...(asked.targetId ? { targetId: asked.targetId } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Audit log">
      <Filters
        onSubmit={() =>
          setAsked({ actor: form.actor.trim(), action: form.action.trim(), targetId: form.targetId.trim() })
        }
      >
        <SmallField label="Who" value={form.actor} onChange={set("actor")} placeholder="nikki" />
        <SmallField label="Action" value={form.action} onChange={set("action")} placeholder="credit." />
        <SmallField label="Target id" value={form.targetId} onChange={set("targetId")} width="w-72" />
      </Filters>
      <PagedView
        query={entries}
        what="the audit log"
        items={(page) => page.entries}
        empty={
          <Empty title="Nothing matches">
            Every admin action, operator command and certificate change is here.
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Audit log"
            columns={["When", "Who", "Action", "Target", "Reason", "Before", "After"]}
          >
            {rows.map((entry) => (
              <Row key={entry.id}>
                <Cell>
                  <RelativeTime value={entry.createdAt} />
                </Cell>
                <Cell>
                  {entry.actorName}
                  <span className="block text-caption text-text-2">{entry.actorKind}</span>
                </Cell>
                <Cell className="font-medium">{entry.action}</Cell>
                <Cell>
                  {entry.targetType}
                  <span
                    className="tabular block max-w-48 truncate text-caption text-text-2"
                    title={entry.targetId}
                  >
                    {entry.targetId}
                  </span>
                </Cell>
                <Cell className="max-w-64">{entry.reason}</Cell>
                <Cell className="tabular max-w-64 text-caption break-all">{change(entry.before)}</Cell>
                <Cell className="tabular max-w-64 text-caption break-all">{change(entry.after)}</Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
    </AdminPage>
  );
}
