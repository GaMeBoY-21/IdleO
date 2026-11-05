import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useLocation } from "wouter";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet, refusal } from "./ActionSheet";
import { ADMIN, adminApi, adminData, asAction, type Schemas } from "./api";
import { ListBar } from "./Lists";
import { useListParam, useListQuery, useListView } from "./listView";
import { ActionButton, useCan } from "./permissions";
import { AdminPage, Cell, Empty, Pill, QueryView, Row, Section, SmallField, SmallSelect, Table } from "./ui";

type Item = Schemas["AdminQueueItem"];
type BulkAsked = Schemas["BulkAsked"];
type Macro = Schemas["AdminMacro"];

const COLUMNS = ["", "What", "Kind", "Priority", "Due", "Who's on it", "At stake", ""];

const KINDS: [string, string][] = [
  ["", "Every kind"],
  ["ticket", "Tickets and grievances"],
  ["data-request", "Data requests"],
  ["chargeback", "Chargebacks"],
  ["abuse", "Abuse"],
  ["report", "Reports"],
  ["review-minute", "Review minutes"],
  ["verification", "Verification"],
  ["approval", "Approvals"],
  ["appeal", "Appeals"],
];

const KIND_WORDS = Object.fromEntries(KINDS);

const VIEWS: [string, string][] = [
  ["all", "Everything"],
  ["mine", "My work"],
  ["unassigned", "Unassigned"],
  ["breaching", "Breaching soon"],
];

/** Its due time in words, never colour alone: past it, paused, or none. */
function Due({ item }: { item: Item }) {
  if (item.paused) {
    return <Pill>Paused, waiting on the person</Pill>;
  }
  if (!item.dueAt) {
    return <>—</>;
  }
  return item.breachedAt ? (
    <Pill tone="danger">
      Past its target, <RelativeTime value={item.dueAt} />
    </Pill>
  ) : (
    <RelativeTime value={item.dueAt} />
  );
}

/**
 * The one inbox (ADR 0053): everything waiting for an admin, whichever module holds it, by priority and then the soonest
 * due. Claim it, and others see who's on it; open it on its own screen to decide it. Select many for a bulk action.
 */
export function Inbox() {
  const client = useQueryClient();
  const can = useCan();
  const [listView, setListView] = useListParam("view", "all");
  const [type, setType] = useListParam("type", "");
  const view = useListView();
  const [problem, setProblem] = useState<unknown>(null);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [overriding, setOverriding] = useState<Item | null>(null);
  const [priority, setPriority] = useState("high");
  const [replying, setReplying] = useState<Item | null>(null);
  const items = useListQuery([ADMIN, "inbox", listView, type, view], "items", (cursor?: string) =>
    adminData(
      adminApi.GET("/admin/v1/queue", {
        params: {
          query: { view: listView, ...(type ? { type } : {}), ...view, ...(cursor ? { cursor } : {}) },
        },
      }),
    ),
  );
  const [cursor, setCursor] = useState(0);
  const [anchor, setAnchor] = useState<number | null>(null);
  const [showKeys, setShowKeys] = useState(false);
  const [, navigate] = useLocation();
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  const act = useMutation({
    mutationFn: async ({ item, action }: { item: Item; action: "claim" | "release" }) => {
      const path =
        action === "claim"
          ? ("/admin/v1/queue/{id}/claim" as const)
          : ("/admin/v1/queue/{id}/release" as const);
      await asAction(crypto.randomUUID(), item, () =>
        adminData(adminApi.POST(path, { params: { path: { id: item.id } } })),
      );
    },
    onError: (failed) => {
      setProblem(failed);
      void refresh();
    },
    onSuccess: () => {
      setProblem(null);
      void refresh();
    },
  });
  const toggle = (id: string) =>
    setSelected((now) => {
      const next = new Set(now);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  const rows = items.data?.items ?? [];
  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || isTyping(event.target)) {
        return;
      }
      const item = rows[cursor];
      switch (event.key) {
        case "j":
          setCursor((now) => Math.min(now + 1, Math.max(rows.length - 1, 0)));
          break;
        case "k":
          setCursor((now) => Math.max(now - 1, 0));
          break;
        case "e":
          if (item && !item.assigneeId) {
            act.mutate({ item, action: "claim" });
          }
          break;
        case "r":
          if (item) {
            navigate(item.link);
          }
          break;
        case "m":
          if (item) {
            setReplying(item);
          }
          break;
        case "x":
          if (item) {
            toggle(item.id);
            setAnchor(cursor);
          }
          break;
        case "X":
          if (anchor !== null) {
            const [from, to] = anchor < cursor ? [anchor, cursor] : [cursor, anchor];
            setSelected((now) => new Set([...now, ...rows.slice(from, to + 1).map((row) => row.id)]));
          }
          break;
        case "?":
          setShowKeys((now) => !now);
          break;
        case "Escape":
          setShowKeys(false);
          break;
        default:
          return;
      }
      event.preventDefault();
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  });
  return (
    <AdminPage title="Inbox">
      {showKeys && <KeyList />}
      <div className="flex flex-wrap items-end gap-3">
        <SmallSelect label="Show" value={listView} onChange={setListView} options={VIEWS} />
        <SmallSelect label="Kind of work" value={type} onChange={setType} options={KINDS} />
      </div>
      <ListBar list="inbox" columns={COLUMNS} extra={type ? { type } : {}} />
      {problem !== null && <Banner tone="danger">{refusal(problem)}</Banner>}
      {selected.size > 0 && can("queues.bulk") && (
        <BulkBar ids={[...selected]} onDone={() => setSelected(new Set())} />
      )}
      <QueryView
        query={items}
        what="the inbox"
        isEmpty={(found) => found.items.length === 0}
        empty={
          <Empty title={listView === "mine" ? "Nothing on you" : "Nothing waiting"}>
            What needs an admin arrives here by itself, from every part of the service.
          </Empty>
        }
      >
        {(found) => (
          <Table caption="Inbox" sorts={{ What: "type", Priority: "priority", Due: "due" }} columns={COLUMNS}>
            {found.items.map((item, index) => (
              <Row key={item.id} className={index === cursor ? "bg-raised" : undefined}>
                <Cell>
                  <label className="inline-flex min-h-touch min-w-touch items-center justify-center">
                    <span className="sr-only">Select {item.title}</span>
                    <input type="checkbox" checked={selected.has(item.id)} onChange={() => toggle(item.id)} />
                  </label>
                </Cell>
                <Cell>
                  <Link
                    href={item.link}
                    className="inline-flex min-h-touch items-center font-semibold underline-offset-4 hover:underline"
                  >
                    {item.title}
                  </Link>
                  {item.runningSession && (
                    <span className="block text-caption text-text-2">A session is running</span>
                  )}
                </Cell>
                <Cell>{KIND_WORDS[item.type] ?? item.type}</Cell>
                <Cell>
                  <Pill tone={item.priority === "urgent" ? "danger" : "plain"}>{item.priority}</Pill>
                  {item.overridden && (
                    <span className="block text-caption text-text-2" title={item.overrideReason ?? undefined}>
                      Set by an admin
                    </span>
                  )}
                </Cell>
                <Cell>
                  <Due item={item} />
                </Cell>
                <Cell>{item.assigneeName ? `${item.assigneeName} is on it` : "No one yet"}</Cell>
                <Cell>{item.moneyPaise > 0 ? <Money paise={item.moneyPaise} /> : "—"}</Cell>
                <Cell>
                  <div className="flex flex-wrap gap-1">
                    {item.assigneeId ? (
                      <ActionButton
                        permission="queues.claim"
                        variant="quiet"
                        onClick={() => act.mutate({ item, action: "release" })}
                      >
                        Let go<span className="sr-only"> of {item.title}</span>
                      </ActionButton>
                    ) : (
                      <ActionButton
                        permission="queues.claim"
                        variant="quiet"
                        onClick={() => act.mutate({ item, action: "claim" })}
                      >
                        Claim<span className="sr-only"> {item.title}</span>
                      </ActionButton>
                    )}
                    <ActionButton permission="queues.claim" variant="quiet" onClick={() => setReplying(item)}>
                      Macro<span className="sr-only"> for {item.title}</span>
                    </ActionButton>
                    <ActionButton
                      permission="queues.claim"
                      variant="quiet"
                      onClick={() => setOverriding(item)}
                    >
                      Priority<span className="sr-only"> of {item.title}</span>
                    </ActionButton>
                  </div>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      {replying && <MacroSheet item={replying} onClose={() => setReplying(null)} />}
      <ActionSheet
        open={overriding !== null}
        onOpenChange={(open) => {
          if (!open) {
            setOverriding(null);
          }
        }}
        title="Set its priority?"
        change={overriding ? `${overriding.title} is ${overriding.priority} now.` : ""}
        confirm="Set it"
        {...(overriding ? { record: overriding } : {})}
        extra={
          <SmallSelect
            label="Priority"
            value={priority}
            onChange={setPriority}
            options={[
              ["urgent", "Urgent"],
              ["high", "High"],
              ["normal", "Normal"],
              ["low", "Low"],
            ]}
          />
        }
        onConfirm={async (reason) => {
          if (overriding) {
            await adminData(
              adminApi.POST("/admin/v1/queue/{id}/priority", {
                params: { path: { id: overriding.id } },
                body: { priority, reason },
              }),
            );
            setOverriding(null);
            await refresh();
          }
        }}
      />
      {can("macros.manage") && <Macros />}
      {can("queues.read") && <Targets />}
    </AdminPage>
  );
}

/** Whether a key press is someone typing, not a shortcut. */
function isTyping(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName))
  );
}

const KEYS: [string, string][] = [
  ["j, k", "Next, previous"],
  ["e", "Claim it"],
  ["r", "Open it to reply"],
  ["m", "Apply a macro"],
  ["x", "Select it"],
  ["Shift-x", "Select from the last one selected to here"],
  ["?", "Show or hide these"],
];

function KeyList() {
  return (
    <Section title="Keys">
      <dl className="flex flex-col gap-1 text-small">
        {KEYS.map(([key, what]) => (
          <div key={key} className="flex gap-3">
            <dt className="tabular w-16 font-semibold">{key}</dt>
            <dd>{what}</dd>
          </div>
        ))}
      </dl>
    </Section>
  );
}

/** Choosing a macro for one item, with its words as the person would read them. */
function MacroSheet({ item, onClose }: { item: Item; onClose: () => void }) {
  const client = useQueryClient();
  const [macro, setMacro] = useState("");
  const macros = useQuery({
    queryKey: [ADMIN, "macros", item.type],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/queue/macros", { params: { query: { type: item.type } } })),
  });
  const preview = useQuery({
    queryKey: [ADMIN, "macro-preview", item.id, macro],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/queue/{id}/macros/{macro}", {
          params: { path: { id: item.id, macro } },
        }),
      ),
    enabled: macro !== "",
  });
  return (
    <ActionSheet
      open
      onOpenChange={(open) => {
        if (!open) {
          onClose();
        }
      }}
      title="Apply a macro?"
      change={preview.data?.body ? preview.data.body : "Choose one to read what it says."}
      confirm="Apply it"
      record={item}
      extra={
        <SmallSelect
          label="Macro"
          value={macro}
          onChange={setMacro}
          options={[
            ["", "Choose…"],
            ...(macros.data?.macros.map((each): [string, string] => [each.id, each.name]) ?? []),
          ]}
        />
      }
      onConfirm={async () => {
        await adminData(
          adminApi.POST("/admin/v1/queue/{id}/macro", {
            params: { path: { id: item.id } },
            body: { macro },
          }),
        );
        onClose();
        await client.invalidateQueries({ queryKey: [ADMIN] });
      }}
    />
  );
}

const WORDS: Record<string, string> = {
  assign: "Assign",
  tag: "Tag",
  priority: "Set the priority",
  close: "Close",
  macro: "Apply a macro",
  warn: "Warn",
};

/** What a shape says, for the preview. */
function shape(value: Schemas["BulkShape"]): string {
  return `${value.state}, ${value.priority}${value.assignee ? ", someone on it" : ""}${value.tagged ? ", tagged" : ""}`;
}

/**
 * A bulk action: choose it, read what each item will become with the counts, type the count back. Over 20 items goes to
 * another admin first. It then runs as one batch, with its progress, and what can be undone says for how long.
 */
function BulkBar({ ids, onDone }: { ids: string[]; onDone: () => void }) {
  const client = useQueryClient();
  const [kind, setKind] = useState("assign");
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
          adminApi.POST("/admin/v1/queue/bulk/preview", { body: { items: ids, action: action() } }),
        ),
      );
    } catch (failed) {
      setProblem(failed);
    }
  };
  const run = async (reason: string) => {
    const { data, error, response } = await adminApi.POST("/admin/v1/queue/bulk", {
      body: { items: ids, action: action(), confirm: String(ids.length), reason },
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
          options={Object.entries(WORDS)}
        />
        {kind !== "close" && (
          <SmallField
            label={
              kind === "assign"
                ? "Admin's id (empty lets go)"
                : kind === "tag"
                  ? "Tag"
                  : kind === "macro"
                    ? "Macro's id"
                    : "urgent, high, normal or low"
            }
            value={value}
            onChange={setValue}
          />
        )}
        <Button variant="ghost" onClick={() => void look()}>
          Preview
        </Button>
        <Button variant="quiet" onClick={onDone}>
          Clear
        </Button>
      </div>
      {problem !== null && <Banner tone="danger">{refusal(problem)}</Banner>}
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
        title={`${WORDS[kind]}: ${preview?.willChange ?? 0} will change, ${preview?.unchanged ?? 0} won't`}
        change={
          <ul className="flex max-h-64 flex-col gap-1 overflow-y-auto text-small">
            {preview?.changes.map((change) => (
              <li key={change.item}>
                <span className="font-medium">{change.title}</span>:{" "}
                {change.applies ? `${shape(change.before)} → ${shape(change.after)}` : change.why}
              </li>
            ))}
          </ul>
        }
        confirm="Run it"
        typed={String(ids.length)}
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

/** The macros: per kind of work, each edit a new version. */
function Macros() {
  const client = useQueryClient();
  const macros = useQuery({
    queryKey: [ADMIN, "macros"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/queue/macros")),
  });
  const [form, setForm] = useState({ type: "ticket", name: "", body: "" });
  const [problem, setProblem] = useState<unknown>(null);
  const [retiring, setRetiring] = useState<Macro | null>(null);
  const add = async () => {
    setProblem(null);
    try {
      await adminData(adminApi.POST("/admin/v1/queue/macros", { body: form }));
      setForm({ ...form, name: "", body: "" });
      await client.invalidateQueries({ queryKey: [ADMIN, "macros"] });
    } catch (failed) {
      setProblem(failed);
    }
  };
  return (
    <Section title="Macros">
      <QueryView
        query={macros}
        what="the macros"
        isEmpty={(found) => found.macros.length === 0}
        empty={<Empty title="No macros yet">A reply you send often, with its changes, in one step.</Empty>}
      >
        {(found) => (
          <Table caption="Macros" columns={["Name", "Kind", "Version", "Says", ""]}>
            {found.macros.map((macro) => (
              <Row key={macro.id}>
                <Cell className="font-medium">{macro.name}</Cell>
                <Cell>{KIND_WORDS[macro.type] ?? macro.type}</Cell>
                <Cell className="tabular">{macro.number}</Cell>
                <Cell className="max-w-96">{macro.body ?? "—"}</Cell>
                <Cell>
                  <ActionButton permission="macros.manage" variant="quiet" onClick={() => setRetiring(macro)}>
                    Retire<span className="sr-only"> {macro.name}</span>
                  </ActionButton>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <SmallSelect
          label="For"
          value={form.type}
          onChange={(type) => setForm({ ...form, type })}
          options={KINDS.filter(([kind]) => kind !== "")}
        />
        <SmallField label="Name" value={form.name} onChange={(name) => setForm({ ...form, name })} />
        <SmallField
          label="Says ({{firstName}}, {{amount}}, {{link}})"
          value={form.body}
          onChange={(body) => setForm({ ...form, body })}
          width="w-96"
        />
        <Button type="submit" variant="ghost">
          Add
        </Button>
      </form>
      {problem !== null && <Banner tone="danger">{refusal(problem)}</Banner>}
      <ActionSheet
        open={retiring !== null}
        onOpenChange={(open) => {
          if (!open) {
            setRetiring(null);
          }
        }}
        title="Retire this macro?"
        change={retiring?.name ?? ""}
        confirm="Retire it"
        {...(retiring ? { record: retiring } : {})}
        onConfirm={async () => {
          if (retiring) {
            await adminData(
              adminApi.POST("/admin/v1/queue/macros/{id}/retire", { params: { path: { id: retiring.id } } }),
            );
            setRetiring(null);
            await client.invalidateQueries({ queryKey: [ADMIN, "macros"] });
          }
        }}
      />
    </Section>
  );
}

const minutes = (value: number | null | undefined) => {
  if (value == null) {
    return "—";
  }
  return value >= 1440 ? `${value / 1440} days` : value >= 60 ? `${value / 60} hours` : `${value} minutes`;
};

/** The targets the timers run to. The Owner can change them; the ones a law sets say so. */
function Targets() {
  const client = useQueryClient();
  const targets = useQuery({
    queryKey: [ADMIN, "sla"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/queue/sla")),
  });
  const [changing, setChanging] = useState<Schemas["AdminSlaTarget"] | null>(null);
  const [first, setFirst] = useState("");
  const [resolve, setResolve] = useState("");
  return (
    <Section title="Targets">
      <QueryView query={targets} what="the targets">
        {(found) => (
          <Table caption="Targets" columns={["For", "First reply", "To resolve", "Pauses", "", ""]}>
            {found.targets.map((target) => (
              <Row key={target.sla}>
                <Cell className="font-medium">{target.sla.replace("-", " ")}</Cell>
                <Cell>{minutes(target.firstMinutes)}</Cell>
                <Cell>{minutes(target.resolveMinutes)}</Cell>
                <Cell>{target.pauses ? "While waiting on the person" : "Never"}</Cell>
                <Cell>{target.legal ? <Pill>A law sets this</Pill> : ""}</Cell>
                <Cell>
                  <ActionButton
                    permission="queues.sla"
                    variant="quiet"
                    onClick={() => {
                      setFirst(target.firstMinutes == null ? "" : String(target.firstMinutes));
                      setResolve(target.resolveMinutes == null ? "" : String(target.resolveMinutes));
                      setChanging(target);
                    }}
                  >
                    Change<span className="sr-only"> {target.sla}</span>
                  </ActionButton>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      <ActionSheet
        open={changing !== null}
        onOpenChange={(open) => {
          if (!open) {
            setChanging(null);
          }
        }}
        title="Change this target?"
        change={
          changing?.legal
            ? "A law sets this one. Change it only with a lawyer's say: running timers follow it on the next pass."
            : "Running timers follow it on the next pass."
        }
        confirm="Change it"
        {...(changing ? { record: changing } : {})}
        extra={
          <>
            <SmallField
              label="First reply, in minutes"
              value={first}
              onChange={setFirst}
              inputMode="numeric"
            />
            <SmallField
              label="To resolve, in minutes"
              value={resolve}
              onChange={setResolve}
              inputMode="numeric"
            />
          </>
        }
        onConfirm={async (reason) => {
          if (changing) {
            await adminData(
              adminApi.POST("/admin/v1/queue/sla/{sla}", {
                params: { path: { sla: changing.sla } },
                body: {
                  reason,
                  pauses: changing.pauses,
                  ...(first ? { firstMinutes: Number(first) } : {}),
                  ...(resolve ? { resolveMinutes: Number(resolve) } : {}),
                },
              }),
            );
            setChanging(null);
            await client.invalidateQueries({ queryKey: [ADMIN, "sla"] });
          }
        }}
      />
    </Section>
  );
}
