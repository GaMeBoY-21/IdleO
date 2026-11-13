import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { StatusChip } from "@/components/ui/StatusChip";
import type { components } from "@/api/schema.gen";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { ActionButton } from "./permissions";
import { pauseEnd, PAUSE_CHOICES, type PauseChoice } from "./rules";
import { AdminPage, Cell, Id, QueryView, Row, Section, SmallSelect, Table } from "./ui";

type Flag = components["schemas"]["AdminFlag"];

const COLUMNS = ["Flag", "State", "If the flags can't be read", "Last change", ""];

/**
 * The kill switches and maintenance mode (ADR 0056). Pausing one is immediate, with a factor and a reason, and
 * optionally for a while; running it again is a request another admin approves, and with no one else to, it runs at
 * once. Every switch is enforced on the server, so this page only ever asks.
 */
export function Flags() {
  const client = useQueryClient();
  const [acting, setActing] = useState<{ flag: Flag; action: "pause" | "resume" } | null>(null);
  const [choice, setChoice] = useState<PauseChoice>("open");
  const flags = useQuery({
    queryKey: [ADMIN, "flags"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/flags")),
    refetchInterval: 10_000,
  });
  const flag = acting?.flag;
  return (
    <AdminPage title="Flags">
      <Section title="Kill switches and maintenance">
        <QueryView query={flags} what="the flags">
          {(list) => (
            <Table caption="Every flag, what it stops and its state" columns={COLUMNS}>
              {list.items.map((item) => (
                <Row key={item.id}>
                  <Cell>
                    <span className="font-semibold">{item.id}</span>
                    <span className="block text-caption text-ink-subtle">
                      {item.owner} · {item.description}
                    </span>
                  </Cell>
                  <Cell>
                    {item.paused ? (
                      <StatusChip
                        size="sm"
                        domain="flag"
                        state="paused"
                        label={item.id === "maintenance" ? "On" : undefined}
                      />
                    ) : (
                      <StatusChip
                        size="sm"
                        domain="flag"
                        state={item.id === "maintenance" ? "off" : "running"}
                      />
                    )}
                    {item.paused && item.until && (
                      <span className="block text-caption text-ink-subtle">
                        {item.resumeAutomatically ? "runs again" : "expected back"}{" "}
                        {formatDateTime(item.until)}
                      </span>
                    )}
                  </Cell>
                  <Cell className="text-small">
                    {item.pausedIfUnknown ? "paused" : "running"}
                    {item.emailsOwners && (
                      <span className="block text-caption text-ink-subtle">emails the Owners</span>
                    )}
                  </Cell>
                  <Cell className="text-small">
                    {item.changedAt ? (
                      <>
                        <RelativeTime value={item.changedAt} />
                        {item.changedBy ? (
                          <span className="block">
                            by <Id value={item.changedBy} page="users" />
                          </span>
                        ) : (
                          <span className="block text-ink-subtle">by the system</span>
                        )}
                        <span className="block text-caption text-ink-subtle">{item.reason}</span>
                      </>
                    ) : (
                      <span className="text-ink-subtle">never</span>
                    )}
                  </Cell>
                  <Cell>
                    {item.paused ? (
                      <ActionButton
                        permission="flags.change"
                        variant="ghost"
                        onClick={() => setActing({ flag: item, action: "resume" })}
                      >
                        {item.id === "maintenance" ? "End maintenance" : "Run again"}
                      </ActionButton>
                    ) : (
                      <ActionButton
                        permission="flags.change"
                        onClick={() => {
                          setChoice("open");
                          setActing({ flag: item, action: "pause" });
                        }}
                      >
                        {item.id === "maintenance" ? "Start maintenance" : "Pause"}
                      </ActionButton>
                    )}
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
        record={flag}
        destructive={acting?.action === "pause"}
        title={
          acting?.action === "resume"
            ? `Run ${flag?.id ?? ""} again?`
            : flag?.id === "maintenance"
              ? "Start maintenance?"
              : `Pause ${flag?.id ?? ""}?`
        }
        change={
          acting?.action === "resume"
            ? "Another admin approves; with no one else to, it runs again at once. Every instance knows within seconds."
            : `${flag?.description ?? ""} It takes effect on every instance within seconds; running sessions carry on.`
        }
        extra={
          acting?.action === "pause" ? (
            <SmallSelect
              label="For how long"
              value={choice}
              onChange={(value) => setChoice(value as PauseChoice)}
              options={PAUSE_CHOICES.map((option) => [option.value, option.label])}
            />
          ) : null
        }
        confirm={acting?.action === "resume" ? "Ask to run again" : "Pause now"}
        onConfirm={async (reason) => {
          const id = acting!.flag.id;
          if (acting!.action === "pause") {
            const end = pauseEnd(choice, new Date());
            await adminData(
              adminApi.POST("/admin/v1/flags/{id}/pause", {
                params: { path: { id } },
                body: {
                  reason,
                  until: end?.toISOString() ?? null,
                  resumeAutomatically: end !== null && choice !== "open",
                },
              }),
            );
          } else {
            await adminData(
              adminApi.POST("/admin/v1/flags/{id}/resume", { params: { path: { id } }, body: { reason } }),
            );
          }
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </AdminPage>
  );
}
