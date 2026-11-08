import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { ListScope, useListView } from "./listView";
import { ActionButton } from "./permissions";
import { Cell, Empty, PagedView, Pill, Row, Section, SmallField, SmallSelect, Table } from "./ui";

const AUDIENCES: [string, string][] = [
  ["RENTERS", "Every renter"],
  ["HOSTS", "Every host's owner"],
  ["TAG", "People with a tag"],
  ["AGENT_VERSIONS", "Owners on an agent version"],
];

const WHEN: [string, string][] = [
  ["0", "Now, once approved"],
  ["60", "In an hour"],
  ["180", "In three hours"],
  ["1440", "Tomorrow, this time"],
];

/**
 * Broadcast notices (ADR 0056): the written notice for many, to renters, hosts' owners, a tag or an agent-version range,
 * at a time, in the app and by email when asked. Each is an approved request; one not sent yet can be cancelled.
 */
export function Broadcasts() {
  return (
    <ListScope.Provider value="broadcasts">
      <BroadcastList />
    </ListScope.Provider>
  );
}

function BroadcastList() {
  const client = useQueryClient();
  const view = useListView();
  const [writing, setWriting] = useState(false);
  const [cancelling, setCancelling] = useState<{ id: string; subject: string; version: number } | null>(null);
  const [form, setForm] = useState({
    subject: "",
    body: "",
    audience: "HOSTS",
    tag: "",
    minAgentVersion: "",
    maxAgentVersion: "",
    email: "no",
    when: "0",
  });
  const list = useInfiniteQuery({
    queryKey: [ADMIN, "broadcasts", view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/broadcasts", {
          params: { query: { ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <Section title="Broadcasts">
      <div className="flex justify-end">
        <ActionButton permission="broadcasts.send" onClick={() => setWriting(true)}>
          Write a broadcast
        </ActionButton>
      </div>
      <PagedView
        query={list}
        what="broadcasts"
        items={(page) => page.items}
        empty={
          <Empty title="No broadcasts yet">A broadcast reaches many at once, in the app and by email.</Empty>
        }
      >
        {(rows) => (
          <Table caption="Broadcasts" list="broadcasts" columns={["Subject", "To", "When", "State", ""]}>
            {rows.map((item) => (
              <Row key={item.id}>
                <Cell>
                  <span className="font-medium">{item.subject}</span>
                  <span className="block text-caption text-text-2">
                    {item.email ? "app and email" : "app only"}
                  </span>
                </Cell>
                <Cell className="text-small">
                  {item.audience === "tag"
                    ? `tag ${item.tag ?? ""}`
                    : item.audience === "agent_versions"
                      ? `agent ${item.minAgentVersion ?? "…"} to ${item.maxAgentVersion ?? "…"}`
                      : item.audience}
                </Cell>
                <Cell>{formatDateTime(item.sendAt)}</Cell>
                <Cell>
                  <Pill
                    tone={item.state === "sent" ? "running" : item.state === "cancelled" ? "plain" : "plain"}
                  >
                    {item.state}
                  </Pill>
                  {item.reached !== null && item.reached !== undefined && (
                    <span className="block text-caption text-text-2">reached {item.reached}</span>
                  )}
                  {item.sentAt && (
                    <span className="block text-caption text-text-2">
                      <RelativeTime value={item.sentAt} />
                    </span>
                  )}
                </Cell>
                <Cell>
                  {item.state === "scheduled" && (
                    <ActionButton
                      permission="broadcasts.send"
                      variant="ghost"
                      onClick={() =>
                        setCancelling({ id: item.id, subject: item.subject, version: item.version })
                      }
                    >
                      Cancel
                    </ActionButton>
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      <ActionSheet
        open={writing}
        onOpenChange={setWriting}
        title="Write a broadcast"
        change="Another admin approves it; it goes at its time to everyone it's for, once, in the app and by email if you ask."
        extra={
          <div className="flex flex-col gap-3">
            <SmallField
              label="Subject"
              value={form.subject}
              onChange={(value) => setForm({ ...form, subject: value })}
              width="w-full"
            />
            <SmallField
              label="Words"
              value={form.body}
              onChange={(value) => setForm({ ...form, body: value })}
              width="w-full"
            />
            <SmallSelect
              label="To"
              value={form.audience}
              onChange={(value) => setForm({ ...form, audience: value })}
              options={AUDIENCES}
            />
            {form.audience === "TAG" && (
              <SmallField
                label="Tag"
                value={form.tag}
                onChange={(value) => setForm({ ...form, tag: value })}
              />
            )}
            {form.audience === "AGENT_VERSIONS" && (
              <div className="flex gap-2">
                <SmallField
                  label="From version"
                  value={form.minAgentVersion}
                  onChange={(value) => setForm({ ...form, minAgentVersion: value })}
                  width="w-28"
                />
                <SmallField
                  label="To version"
                  value={form.maxAgentVersion}
                  onChange={(value) => setForm({ ...form, maxAgentVersion: value })}
                  width="w-28"
                />
              </div>
            )}
            <SmallSelect
              label="By email too"
              value={form.email}
              onChange={(value) => setForm({ ...form, email: value })}
              options={[
                ["no", "No, in the app only"],
                ["yes", "Yes, and in the app"],
              ]}
            />
            <SmallSelect
              label="When"
              value={form.when}
              onChange={(value) => setForm({ ...form, when: value })}
              options={WHEN}
            />
          </div>
        }
        confirm="Ask to send"
        onConfirm={async (reason) => {
          if (form.subject.trim() === "" || form.body.trim() === "") {
            throw new SheetProblem("Give it a subject and words.");
          }
          const minutes = Number(form.when);
          await adminData(
            adminApi.POST("/admin/v1/broadcasts", {
              body: {
                subject: form.subject.trim(),
                body: form.body.trim(),
                audience: form.audience as "RENTERS" | "HOSTS" | "TAG" | "AGENT_VERSIONS",
                tag: form.audience === "TAG" ? form.tag.trim() : null,
                minAgentVersion:
                  form.audience === "AGENT_VERSIONS" && form.minAgentVersion.trim()
                    ? form.minAgentVersion.trim()
                    : null,
                maxAgentVersion:
                  form.audience === "AGENT_VERSIONS" && form.maxAgentVersion.trim()
                    ? form.maxAgentVersion.trim()
                    : null,
                email: form.email === "yes",
                sendAt: minutes === 0 ? null : new Date(Date.now() + minutes * 60_000).toISOString(),
                reason,
              },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
      <ActionSheet
        open={cancelling !== null}
        onOpenChange={(open) => !open && setCancelling(null)}
        record={cancelling ?? undefined}
        title={`Cancel “${cancelling?.subject ?? ""}”?`}
        change="It won't go to anyone."
        confirm="Cancel it"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/broadcasts/{id}/cancel", {
              params: { path: { id: cancelling!.id } },
              body: { reason },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}
