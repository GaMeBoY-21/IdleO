import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { StatusChip, Tag } from "@/components/ui/StatusChip";
import { Link } from "wouter";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, asAction, type Schemas } from "./api";
import { activeRowKey, useScreenKeys } from "./Keys";
import { ListBar } from "./Lists";
import { useListParam, useListQuery, useListView } from "./listView";
import { ActionButton, useCan } from "./permissions";
import {
  AdminPage,
  Cell,
  Empty,
  QueryView,
  Row,
  Section,
  SmallField,
  SmallSelect,
  Table,
  Problem,
} from "./ui";

type Item = Schemas["AdminQueueItem"];
type Macro = Schemas["AdminMacro"];

const COLUMNS = ["What", "Kind", "Priority", "Due", "Who's on it", "At stake", ""];

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
    return <StatusChip size="sm" domain="due" state="paused" />;
  }
  if (!item.dueAt) {
    return <>—</>;
  }
  return item.breachedAt ? (
    <StatusChip
      size="sm"
      domain="due"
      state="overdue"
      label={
        <>
          Past its target, <RelativeTime value={item.dueAt} />
        </>
      }
    />
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
  const [overriding, setOverriding] = useState<Item | null>(null);
  const [priority, setPriority] = useState("high");
  const [replying, setReplying] = useState<Item | null>(null);
  const [assigning, setAssigning] = useState<Item | null>(null);
  const items = useListQuery([ADMIN, "inbox", listView, type, view], "items", (cursor?: string) =>
    adminData(
      adminApi.GET("/admin/v1/queue", {
        params: {
          query: { view: listView, ...(type ? { type } : {}), ...view, ...(cursor ? { cursor } : {}) },
        },
      }),
    ),
  );
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
  const rows = items.data?.items;
  const claim = act.mutate;
  // The list's own keys are the table's; these are what only the inbox does.
  const screenKeys = useMemo(() => {
    const activeItem = () => rows?.find((item) => item.id === activeRowKey());
    return [
      {
        key: "e",
        what: "Claim it",
        run: () => {
          const item = activeItem();
          if (item && !item.assigneeId) {
            claim({ item, action: "claim" });
          }
        },
      },
      {
        key: "m",
        what: "Apply a macro",
        run: () => {
          const item = activeItem();
          if (item) {
            setReplying(item);
          }
        },
      },
    ];
  }, [rows, claim]);
  useScreenKeys(screenKeys);
  return (
    <AdminPage title="Inbox">
      <div className="flex flex-wrap items-end gap-2">
        <SmallSelect label="Show" value={listView} onChange={setListView} options={VIEWS} />
        <SmallSelect label="Kind of work" value={type} onChange={setType} options={KINDS} />
      </div>
      <ListBar list="inbox" extra={type ? { type } : {}} />
      <Problem error={problem} />
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
          <Table
            caption="Inbox"
            list="inbox"
            sorts={{ What: "type", Priority: "priority", Due: "due" }}
            columns={COLUMNS}
          >
            {found.items.map((item) => (
              <Row key={item.id}>
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
                  <StatusChip size="sm" domain="priority" state={item.priority} />
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
                    <ActionButton
                      permission="queues.assign"
                      variant="quiet"
                      onClick={() => setAssigning(item)}
                    >
                      Assign<span className="sr-only"> {item.title}</span>
                    </ActionButton>
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
      {assigning && <AssignSheet item={assigning} onClose={() => setAssigning(null)} />}
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

/** Giving an item to another admin, who must be able to see its kind of work. */
function AssignSheet({ item, onClose }: { item: Item; onClose: () => void }) {
  const client = useQueryClient();
  const admins = useQuery({
    queryKey: [ADMIN, "mentionable"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/notes/mentionable")),
  });
  const [assignee, setAssignee] = useState("");
  const chosen = assignee || admins.data?.admins[0]?.id || "";
  return (
    <ActionSheet
      open
      onOpenChange={(open) => {
        if (!open) {
          onClose();
        }
      }}
      title="Assign this to someone?"
      change={item.title}
      confirm="Assign it"
      record={item}
      extra={
        <SmallSelect
          label="Who"
          value={chosen}
          onChange={setAssignee}
          options={(admins.data?.admins ?? []).map((admin): [string, string] => [admin.id, admin.name])}
        />
      }
      onConfirm={async (reason) => {
        await adminData(
          adminApi.POST("/admin/v1/queue/{id}/assign", {
            params: { path: { id: item.id } },
            body: { assignee: chosen, reason },
          }),
        );
        await client.invalidateQueries({ queryKey: [ADMIN] });
        onClose();
      }}
    />
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
  const [changing, setChanging] = useState<Macro | null>(null);
  const [changed, setChanged] = useState({ name: "", body: "" });
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
          <Table caption="Macros" list="macros" columns={["Name", "Kind", "Version", "Says", ""]}>
            {found.macros.map((macro) => (
              <Row key={macro.id}>
                <Cell className="font-medium">{macro.name}</Cell>
                <Cell>{KIND_WORDS[macro.type] ?? macro.type}</Cell>
                <Cell className="tabular">{macro.number}</Cell>
                <Cell className="max-w-96">{macro.body ?? "—"}</Cell>
                <Cell>
                  <ActionButton
                    permission="macros.manage"
                    variant="quiet"
                    onClick={() => {
                      setChanged({ name: macro.name, body: macro.body ?? "" });
                      setChanging(macro);
                    }}
                  >
                    Change<span className="sr-only"> {macro.name}</span>
                  </ActionButton>
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
        className="flex flex-wrap items-end gap-2"
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
      <Problem error={problem} />
      <ActionSheet
        open={changing !== null}
        onOpenChange={(open) => {
          if (!open) {
            setChanging(null);
          }
        }}
        title="Change this macro?"
        change="It becomes a new version. What was already sent stays as it was."
        confirm="Change it"
        {...(changing ? { record: changing } : {})}
        extra={
          <>
            <SmallField
              label="Macro's name"
              value={changed.name}
              onChange={(name) => setChanged({ ...changed, name })}
            />
            <SmallField
              label="What it says"
              value={changed.body}
              onChange={(body) => setChanged({ ...changed, body })}
              width="w-full"
            />
          </>
        }
        onConfirm={async () => {
          if (changing) {
            await adminData(
              adminApi.POST("/admin/v1/queue/macros/{id}", {
                params: { path: { id: changing.id } },
                body: { name: changed.name, body: changed.body },
              }),
            );
            setChanging(null);
            await client.invalidateQueries({ queryKey: [ADMIN, "macros"] });
          }
        }}
      />
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
                <Cell>{target.legal ? <Tag>A law sets this</Tag> : ""}</Cell>
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
