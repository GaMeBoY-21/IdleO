import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { count } from "@/lib/format";
import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData, type AuditStanding } from "./api";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { ListScope, useListView } from "./listView";
import {
  AdminPage,
  Cell,
  Empty,
  Filters,
  PagedView,
  Row,
  Section,
  SmallField,
  Table,
  QueryView,
  Problem,
} from "./ui";
import { Reveal } from "./Reveal";

const ACCESS_COLUMNS = ["When", "Admin", "Whose", "Field", "Why"];

/** A change, as its JSON, short enough for a row. */
function change(value: Record<string, unknown> | null | undefined): string {
  return value ? JSON.stringify(value) : "—";
}

/**
 * The audit log (ADR 0027, ADR 0051): every privileged action, newest first, read only. Filtered by who (part of a name,
 * or a user id), what (an action, or the start of one: `host.`), the target, the dates, the approval and the request.
 */
export function Audit() {
  const view = useListView();
  const empty = { actor: "", action: "", targetId: "", approvalId: "", requestId: "", from: "", to: "" };
  const [form, setForm] = useState(empty);
  const [asked, setAsked] = useState(form);
  const set = (field: keyof typeof form) => (value: string) => setForm({ ...form, [field]: value });
  const entries = useInfiniteQuery({
    queryKey: [ADMIN, "audit", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/audit", {
          params: {
            query: {
              ...(asked.actor ? { actor: asked.actor } : {}),
              ...(asked.action ? { action: asked.action } : {}),
              ...(asked.targetId ? { targetId: asked.targetId } : {}),
              ...(asked.approvalId ? { approvalId: asked.approvalId } : {}),
              ...(asked.requestId ? { requestId: asked.requestId } : {}),
              ...(asked.from ? { from: new Date(asked.from).toISOString() } : {}),
              ...(asked.to ? { to: new Date(asked.to).toISOString() } : {}),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const columns = ["When", "Who", "Action", "Target", "Reason", "Before", "After", "From"];
  return (
    <AdminPage title="Audit log">
      <Standing />
      <Filters
        onSubmit={() =>
          setAsked(
            Object.fromEntries(
              Object.entries(form).map(([field, value]) => [field, value.trim()]),
            ) as typeof form,
          )
        }
      >
        <SmallField label="Who" value={form.actor} onChange={set("actor")} placeholder="nikki" />
        <SmallField label="Action" value={form.action} onChange={set("action")} placeholder="credit." />
        <SmallField label="Target id" value={form.targetId} onChange={set("targetId")} width="w-72" />
        <SmallField label="Approval" value={form.approvalId} onChange={set("approvalId")} width="w-72" />
        <SmallField label="Request" value={form.requestId} onChange={set("requestId")} width="w-72" />
        <SmallField label="From" value={form.from} onChange={set("from")} placeholder="2026-11-01" />
        <SmallField label="To" value={form.to} onChange={set("to")} placeholder="2026-11-30" />
      </Filters>
      <Section title="Entries">
        <ListBar
          list="audit"
          tags={false}
          extra={Object.fromEntries(
            Object.entries({
              actor: asked.actor,
              action: asked.action,
              targetId: asked.targetId,
              approvalId: asked.approvalId,
              requestId: asked.requestId,
              from: asked.from && new Date(asked.from).toISOString(),
              to: asked.to && new Date(asked.to).toISOString(),
            }).filter(([, value]) => value),
          )}
        />
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
              list="audit"
              sorts={{ When: "createdAt", Action: "action" }}
              columns={columns}
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
                  <Cell className="tabular max-w-48 text-caption break-all">
                    {entry.ip ? <Reveal field="audit_ip" id={entry.id} masked={entry.ip} /> : "—"}
                    {entry.requestId && <span className="block text-text-2">Request {entry.requestId}</span>}
                    {entry.approvalId && (
                      <span className="block text-text-2">Approval {entry.approvalId}</span>
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      </Section>
      <ListScope.Provider value="access">
        <AccessLog />
      </ListScope.Provider>
    </AdminPage>
  );
}

/** How the log stands: its chain's latest check, a button to check it now, and the newest day copied off site. */
function Standing() {
  const client = useQueryClient();
  const standing = useQuery({
    queryKey: [ADMIN, "audit-standing"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/audit/standing")),
  });
  const [checking, setChecking] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const check = async () => {
    setChecking(true);
    setProblem(null);
    try {
      await adminData(adminApi.POST("/admin/v1/audit/verify"));
      await client.invalidateQueries({ queryKey: [ADMIN, "audit-standing"] });
    } catch (failed) {
      setProblem(failed);
    } finally {
      setChecking(false);
    }
  };
  return (
    <Section
      title="The chain"
      action={
        <ActionButton permission="audit.read" variant="ghost" busy={checking} onClick={() => void check()}>
          Check the chain now
        </ActionButton>
      }
    >
      <Problem error={problem} />
      <QueryView query={standing} what="how the audit log stands">
        {(found: AuditStanding) => (
          <div className="flex flex-col gap-2 text-small">
            {found.latestCheck ? (
              found.latestCheck.intact ? (
                <Banner tone="info">
                  Intact: {found.latestCheck.rows.toLocaleString("en-IN")} rows checked in{" "}
                  {found.latestCheck.tookMs} ms, <RelativeTime value={found.latestCheck.checkedAt} />.
                </Banner>
              ) : (
                <Banner tone="danger">
                  Broken at row {found.latestCheck.brokenSeq}: {found.latestCheck.detail}. Follow the audit
                  chain runbook, and change nothing.
                </Banner>
              )
            ) : (
              <p className="text-text-2">It hasn&apos;t been checked yet.</p>
            )}
            <p className="text-text-2">
              {found.copies[0]
                ? `Newest off-site copy: ${found.copies[0].day}, ${count(found.copies[0].rows, "row")}.`
                : "No day has been copied off site yet."}
            </p>
          </div>
        )}
      </QueryView>
    </Section>
  );
}

/** Who viewed or revealed whose data (ADR 0051): for whoever reads this log. */
function AccessLog() {
  const [subject, setSubject] = useState("");
  const [asked, setAsked] = useState("");
  const view = useListView();
  const entries = useInfiniteQuery({
    queryKey: [ADMIN, "access-log", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/personal-data/access-log", {
          params: {
            query: {
              ...(asked ? { subject: asked } : {}),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <Section title="Access to personal data">
      <Filters onSubmit={() => setAsked(subject.trim())}>
        <SmallField label="Person's id" value={subject} onChange={setSubject} width="w-72" />
      </Filters>
      <ListBar list="access-log" tags={false} extra={asked ? { subject: asked } : {}} />
      <PagedView
        query={entries}
        what="the access log"
        items={(page) => page.entries}
        empty={
          <Empty title="Nothing matches">
            Each time an admin opens a person or reveals a value, it&apos;s here.
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Access log"
            list="access-log"
            sorts={{ When: "at", Field: "field" }}
            columns={ACCESS_COLUMNS}
          >
            {rows.map((entry) => (
              <Row key={entry.id}>
                <Cell>
                  <RelativeTime value={entry.at} />
                </Cell>
                <Cell className="tabular text-caption break-all">{entry.adminId}</Cell>
                <Cell className="tabular text-caption break-all">{entry.subjectUserId ?? "—"}</Cell>
                <Cell className="font-medium">{entry.field}</Cell>
                <Cell className="max-w-64">{entry.reason}</Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
    </Section>
  );
}
