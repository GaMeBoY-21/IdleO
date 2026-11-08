import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type Schemas } from "./api";
import { useCan } from "./permissions";
import { Problem, Section, SmallField, SmallSelect } from "./ui";

type BulkAsked = Schemas["BulkAsked"];

const WORDS: Record<string, string> = {
  assign: "Assign",
  tag: "Tag",
  priority: "Set the priority",
  close: "Close",
  macro: "Apply a macro",
  warn: "Warn",
};

const NOT_ASKED: string[] = [];

/** What a bulk action may do from this list (ADR 0057), as the server says; nothing without `queues.bulk`. */
export function useBulkActions(list: string | undefined): string[] {
  const can = useCan();
  const allowed = list !== undefined && can("queues.bulk");
  const lists = useQuery({
    queryKey: [ADMIN, "bulk-lists"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/queue/bulk/lists")),
    enabled: allowed,
    staleTime: 5 * 60_000,
    retry: false,
  });
  return (allowed ? lists.data?.lists.find((each) => each.list === list)?.actions : undefined) ?? NOT_ASKED;
}

/** What a shape says, for the preview. */
function shape(value: Schemas["BulkShape"]): string {
  if (value.state === "") {
    return value.tagged ? "tagged" : "not tagged";
  }
  return `${value.state}, ${value.priority}${value.assignee ? ", someone on it" : ""}${value.tagged ? ", tagged" : ""}`;
}

/**
 * A bulk action, from any list that allows one (ADR 0053, ADR 0057): choose it, read what each row will become with the
 * counts, type the count back. Over 20 goes to another admin first. It then runs as one batch, with its progress, and
 * what can be undone says for how long. Money and enforcement beyond a warning are never among the choices.
 */
export function BulkBar({
  list,
  actions,
  ids,
  onDone,
}: {
  list: string;
  actions: string[];
  ids: string[];
  onDone: () => void;
}) {
  const client = useQueryClient();
  const [kind, setKind] = useState(actions[0] ?? "tag");
  const [value, setValue] = useState("");
  const [preview, setPreview] = useState<Schemas["BulkPreview"] | null>(null);
  const [batch, setBatch] = useState<string | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const action = (): BulkAsked => ({
    kind,
    ...(kind === "assign" && value ? { assignee: value } : {}),
    ...(kind === "tag" ? { tag: value } : {}),
    ...(kind === "priority" ? { priority: value } : {}),
    ...(kind === "macro" ? { macro: value } : {}),
    ...(kind === "warn" ? { template: value } : {}),
  });
  const running = useQuery({
    queryKey: [ADMIN, "bulk", batch],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/queue/bulk/{id}", { params: { path: { id: batch ?? "" } } })),
    enabled: batch !== null,
    refetchInterval: (query) => (query.state.data?.state === "done" ? false : 1000),
  });
  const look = async () => {
    setProblem(null);
    try {
      setPreview(
        await adminData(
          adminApi.POST("/admin/v1/queue/bulk/preview", { body: { list, items: ids, action: action() } }),
        ),
      );
    } catch (failed) {
      setProblem(failed);
    }
  };
  const run = async (reason: string) => {
    const { data, error, response } = await adminApi.POST("/admin/v1/queue/bulk", {
      body: { list, items: ids, action: action(), confirm: String(ids.length), reason },
    });
    // Over 20 items is another admin's to approve: the sheet says so.
    if (response.status === 202) {
      return;
    }
    const started = await adminData(Promise.resolve({ data, error, response }));
    setBatch((started as Schemas["AdminBulkAction"]).id);
    setPreview(null);
    await client.invalidateQueries({ queryKey: [ADMIN] });
  };
  const fieldLabel: Record<string, string> = {
    assign: "Admin's id (empty lets go)",
    tag: "Tag",
    macro: "Macro's id",
    priority: "urgent, high, normal or low",
    warn: "Warning's template",
  };
  return (
    <Section title={`${ids.length} selected`}>
      <div className="flex flex-wrap items-end gap-3">
        <SmallSelect
          label="Do"
          value={kind}
          onChange={(next) => {
            setKind(next);
            setValue("");
          }}
          options={actions.map((each): [string, string] => [each, WORDS[each] ?? each])}
        />
        {kind !== "close" && (
          <SmallField label={fieldLabel[kind] ?? "Value"} value={value} onChange={setValue} />
        )}
        <Button variant="ghost" onClick={() => void look()}>
          Preview
        </Button>
        <Button variant="quiet" onClick={onDone}>
          Clear
        </Button>
      </div>
      <Problem error={problem} />
      {running.data && (
        <p role="status" className="text-small">
          {running.data.state === "done" ? "Finished" : "Running"}: {running.data.done} done,{" "}
          {running.data.skipped} unchanged, {running.data.failed} failed, of {running.data.total}.
          {running.data.undoable && running.data.undoUntil && !running.data.undoneAt && (
            <>
              {" "}
              <UndoButton id={running.data.id} />
            </>
          )}
        </p>
      )}
      <ActionSheet
        open={preview !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPreview(null);
          }
        }}
        title={`${WORDS[kind] ?? kind}: ${preview?.willChange ?? 0} will change, ${preview?.unchanged ?? 0} won't`}
        change={
          <ul className="flex max-h-64 flex-col gap-1 overflow-y-auto text-small">
            {preview?.changes.map((change) => (
              <li key={change.item}>
                <span className="font-medium">{change.title}</span>:{" "}
                {change.applies
                  ? kind === "warn"
                    ? "They'll be warned"
                    : `${shape(change.before)} → ${shape(change.after)}`
                  : change.why}
              </li>
            ))}
          </ul>
        }
        confirm="Run it"
        typed={String(ids.length)}
        typedBecause={
          kind === "macro" || kind === "warn" ? "This can't be undone." : "It can be undone for 10 minutes."
        }
        onConfirm={run}
      />
    </Section>
  );
}

function UndoButton({ id }: { id: string }) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="quiet" onClick={() => setOpen(true)}>
        Undo
      </Button>
      <ActionSheet
        open={open}
        onOpenChange={setOpen}
        title="Undo this?"
        change="It puts back what this changed. It's possible for 10 minutes."
        confirm="Undo it"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/queue/bulk/{id}/undo", { params: { path: { id } }, body: { reason } }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </>
  );
}
