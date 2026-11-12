import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { FilterBar } from "@/components/ui/FilterBar";
import { useMemo, useState } from "react";
import { KeyValueList } from "@/components/ui/KeyValues";
import { StatusChip, Tag } from "@/components/ui/StatusChip";
import { Link, useSearch } from "wouter";
import { buttonClasses } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise, parseRupees } from "@/lib/money";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminCreditGrant, type AdminUser } from "./api";
import { RECORDS } from "./recordQueries";
import {
  AdminPage,
  Cell,
  Empty,
  PagedView,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallField,
  SmallSelect,
  Table,
  Id,
} from "./ui";
import { usePaletteActions } from "./Palette";
import { ActionButton, useCan } from "./permissions";
import { UserTabs } from "./Records";
import { Reveal } from "./Reveal";
import { ListBar } from "./Lists";
import { useListQuery, useListParams, useListView } from "./listView";

/** Users (ADR 0027): find them by email, name or id; one opens with what can be done to it. */
export function Users() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <UserRecord id={id} /> : <UserSearch />;
}

function UserSearch() {
  const [asked, setAsked] = useListParams({ query: "", status: "" });
  const [query, setQuery] = useState(asked.query);
  const [status, setStatus] = useState(asked.status);
  const view = useListView();
  const users = useInfiniteQuery({
    queryKey: [ADMIN, "users", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/users", {
          params: {
            query: {
              ...(asked.query ? { query: asked.query } : {}),
              ...(asked.status ? { status: asked.status } : {}),
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
    <AdminPage title="Users">
      <ListBar list="users" filters={{ query: "query", status: "status" }} />
      <FilterBar onSubmit={() => setAsked({ query: query.trim(), status })}>
        <SmallField label="Email, name or id" value={query} onChange={setQuery} width="w-72" />
        <SmallSelect
          label="Status"
          value={status}
          onChange={setStatus}
          options={[
            ["", "Any"],
            ["active", "Active"],
            ["waiting", "Waiting for an invite"],
            ["suspended", "Suspended"],
          ]}
        />
      </FilterBar>
      <PagedView
        query={users}
        what="users"
        items={(page) => page.users}
        empty={<Empty title="No users match">Try part of an email or a name, or a whole user id.</Empty>}
      >
        {(rows) => (
          <Table
            caption="Users"
            list="users"
            sorts={{ Email: "email", Name: "name", Status: "status", "Signed up": "createdAt" }}
            columns={["Email", "Name", "Status", "Signed up", "Last sign-in", ""]}
          >
            {rows.map((user) => (
              <Row key={user.id}>
                <Cell>{user.email ?? "—"}</Cell>
                <Cell>{user.displayName ?? "—"}</Cell>
                <Cell>
                  <UserStatus user={user} />
                </Cell>
                <Cell>
                  <RelativeTime value={user.createdAt} />
                </Cell>
                <Cell>{user.lastSignInAt ? <RelativeTime value={user.lastSignInAt} /> : "never"}</Cell>
                <Cell>
                  <RowLink href={`/admin/users/?id=${user.id}`}>
                    Open<span className="sr-only"> {user.email ?? user.id}</span>
                  </RowLink>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
    </AdminPage>
  );
}

function UserStatus({ user }: { user: AdminUser }) {
  return (
    <span className="flex flex-wrap gap-1">
      <StatusChip size="sm" domain="account" state={user.status} />
      {user.admin && <Tag>Admin</Tag>}
    </span>
  );
}

type Action = "suspend" | "unsuspend" | "grant" | { reverse: AdminCreditGrant };

function UserRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const can = useCan();
  const user = useQuery(RECORDS.user(id));
  const grants = useListQuery(
    [ADMIN, "grants", id],
    "grants",
    (cursor?: string) =>
      adminData(
        adminApi.GET("/admin/v1/credit-grants", {
          params: { query: { user: id, ...(cursor ? { cursor } : {}) } },
        }),
      ),
    { enabled: can("money.read") },
  );
  const [action, setAction] = useState<Action | null>(null);
  const [erasing, setErasing] = useState(false);
  // Why an account is suspended decides whether its payouts are held (ADR 0041).
  const [cause, setCause] = useState("other");
  const [rupees, setRupees] = useState("");
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });

  const paise = parseRupees(rupees);
  const open = (next: Action) => {
    if (next === "grant") {
      setRupees("");
    }
    if (next === "suspend") {
      setCause("other");
    }
    setAction(next);
  };
  const status = user.data?.status;
  usePaletteActions(
    useMemo(
      () =>
        status === "suspended"
          ? [
              {
                label: "Reinstate this user…",
                permission: "users.unsuspend",
                run: () => setAction("unsuspend"),
              },
            ]
          : status === "active"
            ? [
                {
                  label: "Suspend this user…",
                  permission: "users.suspend",
                  run: () => {
                    setCause("other");
                    setAction("suspend");
                  },
                },
              ]
            : [],
      [status],
    ),
  );

  return (
    <AdminPage
      title="User"
      name={user.data ? (user.data.displayName ?? `…${id.slice(-8)}`) : null}
      trail={[{ label: "Users", href: "/admin/users/" }]}
    >
      <QueryView query={user} what="this user">
        {(found) => (
          <>
            <KeyValueList
              items={[
                [
                  "Email",
                  found.email ? <Reveal key="email" field="email" id={found.id} masked={found.email} /> : "—",
                ],
                ["Name", found.displayName ?? "—"],
                ["Status", <UserStatus key="status" user={found} />],
                ["User id", <Id key="id" value={found.id} />],
                ["Signed up", <RelativeTime key="created" value={found.createdAt} />],
                [
                  "Last sign-in",
                  found.lastSignInAt ? <RelativeTime key="last" value={found.lastSignInAt} /> : "never",
                ],
                [
                  "API keys",
                  <span key="keys" className="tabular">
                    {found.apiKeys}
                  </span>,
                ],
                ["Invited with", found.inviteNote ?? "—"],
                ...(found.status === "suspended" && found.suspensionCause === "host_misconduct"
                  ? ([
                      ["Suspended for", "Misconduct as a host: their payouts are held until it is lifted"],
                    ] as [string, string][])
                  : []),
              ]}
            />
            <div className="flex flex-wrap gap-2">
              {found.status === "suspended" ? (
                <ActionButton permission="users.unsuspend" variant="ghost" onClick={() => open("unsuspend")}>
                  Reinstate
                </ActionButton>
              ) : (
                <ActionButton
                  permission="users.suspend"
                  variant="ghost"
                  onClick={() => open("suspend")}
                  disabled={found.status !== "active"}
                >
                  Suspend
                </ActionButton>
              )}
              <ActionButton permission="money.adjust" variant="ghost" onClick={() => open("grant")}>
                Grant credit
              </ActionButton>
              <Link href={`/admin/sessions/?user=${found.id}`} className={buttonClasses("ghost")}>
                Their sessions
              </Link>
              <Link
                href={`/admin/ledger/?account=renter_wallet&owner=${found.id}`}
                className={buttonClasses("ghost")}
              >
                Their wallet in the ledger
              </Link>
              <ActionButton permission="privacy.legal-order" variant="quiet" onClick={() => setErasing(true)}>
                Erase under a legal order
              </ActionButton>
            </div>
            <ActionSheet
              open={erasing}
              onOpenChange={setErasing}
              record={found}
              typed={found.id.slice(0, 8)}
              destructive
              title={`Erase ${found.email ?? "this person"} under a legal order?`}
              change="Their account is erased at the next run of the erasure job, without their asking and whatever they have open. Name the order in your reason: it stays in the audit log."
              confirm="Erase them"
              onConfirm={async (reason) => {
                await adminData(
                  adminApi.POST("/admin/v1/users/{id}/deletion/legal-order", {
                    params: { path: { id } },
                    body: { reason },
                  }),
                );
              }}
            />
            <ActionSheet
              open={action === "suspend" || action === "unsuspend"}
              onOpenChange={(next) => !next && setAction(null)}
              record={found}
              typed={found.id.slice(0, 8)}
              title={
                action === "unsuspend"
                  ? `Reinstate ${found.email ?? "this user"}?`
                  : `Suspend ${found.email ?? "this user"}?`
              }
              change={
                action === "unsuspend"
                  ? "Their account becomes active again: they can sign in, book and host."
                  : "From their next request, their sign-ins and API keys are refused. Sessions already running carry on."
              }
              confirm={action === "unsuspend" ? "Reinstate" : "Suspend"}
              destructive={action === "suspend"}
              extra={
                action === "suspend" ? (
                  <SmallSelect
                    label="Why"
                    value={cause}
                    onChange={setCause}
                    options={[
                      ["other", "Anything else: their payouts carry on"],
                      ["host_misconduct", "Misconduct as a host: hold their payouts"],
                    ]}
                  />
                ) : null
              }
              onConfirm={async (reason) => {
                if (action === "unsuspend") {
                  await adminData(
                    adminApi.POST("/admin/v1/users/{id}/unsuspend", {
                      params: { path: { id } },
                      body: { reason },
                    }),
                  );
                } else {
                  await adminData(
                    adminApi.POST("/admin/v1/users/{id}/suspend", {
                      params: { path: { id } },
                      body: { reason, cause: cause === "host_misconduct" ? "host_misconduct" : "other" },
                    }),
                  );
                }
                await refresh();
              }}
            />
            <ActionSheet
              open={action === "grant"}
              onOpenChange={(next) => !next && setAction(null)}
              title={`Grant credit to ${found.email ?? "this user"}`}
              change={
                paise === null || paise <= 0
                  ? "Free credit, added to their wallet: at most ₹5,000 a grant, and no wallet above ₹20,000."
                  : `${formatPaise(paise)} of free credit is added to their wallet.`
              }
              confirm="Grant credit"
              extra={
                <SmallField label="Amount in ₹" value={rupees} onChange={setRupees} inputMode="decimal" />
              }
              onConfirm={async (reason) => {
                if (paise === null || paise <= 0) {
                  throw new SheetProblem("Give an amount above ₹0, in rupees and paise, like 500 or 12.50.");
                }
                await adminData(
                  adminApi.POST("/admin/v1/credit-grants", {
                    body: { userId: id, paise, reason },
                  }),
                );
                await refresh();
              }}
            />
          </>
        )}
      </QueryView>
      {/* What's under the record waits for it, so it doesn't sit under the loading one and then jump down. */}
      {user.data && can("money.read") && (
        <Section title="Credit grants">
          <QueryView
            query={grants}
            what="their credit grants"
            isEmpty={(list) => list.grants.length === 0}
            empty={
              <Empty title="No grants yet">
                Free credit granted to them shows here, with who granted it and why.
              </Empty>
            }
          >
            {(list) => (
              <Table
                caption="Credit grants"
                list="credit-grants"
                columns={["Amount", "Reason", "Granted by", "When", "Reversed", ""]}
              >
                {list.grants.map((grant) => (
                  <Row key={grant.id}>
                    <Cell>
                      <Money paise={grant.amountPaise} />
                    </Cell>
                    <Cell>{grant.reason}</Cell>
                    <Cell>{grant.grantedBy}</Cell>
                    <Cell>
                      <RelativeTime value={grant.createdAt} />
                    </Cell>
                    <Cell>
                      {grant.reversedAt ? (
                        <RelativeTime value={grant.reversedAt} prefix={grant.reversedBy ?? ""} />
                      ) : (
                        "—"
                      )}
                    </Cell>
                    <Cell>
                      {grant.reversalId === null && (
                        <ActionButton
                          permission="money.adjust"
                          variant="quiet"
                          onClick={() => open({ reverse: grant })}
                        >
                          Reverse
                          <span className="sr-only"> the grant of {formatPaise(grant.amountPaise)}</span>
                        </ActionButton>
                      )}
                    </Cell>
                  </Row>
                ))}
              </Table>
            )}
          </QueryView>
        </Section>
      )}
      {action !== null && typeof action === "object" && (
        <ActionSheet
          open
          onOpenChange={(next) => !next && setAction(null)}
          record={action.reverse}
          typed={action.reverse.id.slice(0, 8)}
          title={`Reverse the grant of ${formatPaise(action.reverse.amountPaise)}?`}
          change={`${formatPaise(action.reverse.amountPaise)} is taken back from their wallet with an opposite transaction. Refused if credit already spent or held would be needed.`}
          confirm="Reverse the grant"
          destructive
          onConfirm={async (reason) => {
            await adminData(
              adminApi.POST("/admin/v1/credit-grants/{id}/reverse", {
                params: { path: { id: action.reverse.id } },
                body: { reason },
              }),
            );
            await refresh();
          }}
        />
      )}
      {user.data && <UserTabs id={id} can={can} />}
    </AdminPage>
  );
}
