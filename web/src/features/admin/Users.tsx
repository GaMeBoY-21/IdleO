import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Button, buttonClasses } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise, parseRupees } from "@/lib/money";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminCreditGrant, type AdminUser } from "./api";
import {
  AdminPage,
  BackLink,
  Cell,
  Empty,
  Fields,
  Filters,
  PagedView,
  Pill,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

/** Users (ADR 0027): find them by email, name or id; one opens with what can be done to it. */
export function Users() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <UserRecord id={id} /> : <UserSearch />;
}

function UserSearch() {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [asked, setAsked] = useState({ query: "", status: "" });
  const users = useInfiniteQuery({
    queryKey: [ADMIN, "users", asked],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/users", {
          params: {
            query: {
              ...(asked.query ? { query: asked.query } : {}),
              ...(asked.status ? { status: asked.status } : {}),
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
      <Filters onSubmit={() => setAsked({ query: query.trim(), status })}>
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
      </Filters>
      <PagedView
        query={users}
        what="users"
        items={(page) => page.users}
        empty={<Empty title="No users match">Try part of an email or a name, or a whole user id.</Empty>}
      >
        {(rows) => (
          <Table caption="Users" columns={["Email", "Name", "Status", "Signed up", "Last sign-in", ""]}>
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
      <Pill tone={user.status === "suspended" ? "danger" : user.status === "active" ? "running" : "plain"}>
        {user.status}
      </Pill>
      {user.admin && <Pill>admin</Pill>}
    </span>
  );
}

type Action = "suspend" | "unsuspend" | "grant" | { reverse: AdminCreditGrant };

function UserRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const user = useQuery({
    queryKey: [ADMIN, "user", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/users/{id}", { params: { path: { id } } })),
  });
  const grants = useQuery({
    queryKey: [ADMIN, "grants", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/credit-grants", { params: { query: { user: id } } })),
  });
  const [action, setAction] = useState<Action | null>(null);
  // Why an account is suspended decides whether its payouts are held (ADR 0041).
  const [cause, setCause] = useState("other");
  const [rupees, setRupees] = useState("");
  const [key, setKey] = useState("");
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });

  const paise = parseRupees(rupees);
  const open = (next: Action) => {
    if (next === "grant") {
      setRupees("");
      setKey(crypto.randomUUID());
    }
    if (next === "suspend") {
      setCause("other");
    }
    setAction(next);
  };

  return (
    <AdminPage title="User">
      <BackLink href="/admin/users/">← All users</BackLink>
      <QueryView query={user} what="this user">
        {(found) => (
          <>
            <Fields
              items={[
                ["Email", found.email ?? "—"],
                ["Name", found.displayName ?? "—"],
                ["Status", <UserStatus key="status" user={found} />],
                [
                  "User id",
                  <span key="id" className="tabular">
                    {found.id}
                  </span>,
                ],
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
                <Button variant="secondary" onClick={() => open("unsuspend")}>
                  Reinstate
                </Button>
              ) : (
                <Button
                  variant="secondary"
                  onClick={() => open("suspend")}
                  disabled={found.status !== "active"}
                >
                  Suspend
                </Button>
              )}
              <Button variant="secondary" onClick={() => open("grant")}>
                Grant credit
              </Button>
              <Link href={`/admin/sessions/?user=${found.id}`} className={buttonClasses("secondary")}>
                Their sessions
              </Link>
              <Link href={`/admin/money/?owner=${found.id}`} className={buttonClasses("secondary")}>
                Their wallet in the ledger
              </Link>
            </div>
            <ActionSheet
              open={action === "suspend" || action === "unsuspend"}
              onOpenChange={(next) => !next && setAction(null)}
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
                await adminData(
                  action === "unsuspend"
                    ? adminApi.POST("/admin/v1/users/{id}/unsuspend", {
                        params: { path: { id } },
                        body: { reason },
                      })
                    : adminApi.POST("/admin/v1/users/{id}/suspend", {
                        params: { path: { id } },
                        body: { reason, cause: cause === "host_misconduct" ? "host_misconduct" : "other" },
                      }),
                );
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
                    params: { header: { "Idempotency-Key": key } },
                    body: { userId: id, paise, reason },
                  }),
                );
                await refresh();
              }}
            />
          </>
        )}
      </QueryView>
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
                      <Button variant="quiet" onClick={() => open({ reverse: grant })}>
                        Reverse<span className="sr-only"> the grant of {formatPaise(grant.amountPaise)}</span>
                      </Button>
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </QueryView>
      </Section>
      {action !== null && typeof action === "object" && (
        <ActionSheet
          open
          onOpenChange={(next) => !next && setAction(null)}
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
    </AdminPage>
  );
}
