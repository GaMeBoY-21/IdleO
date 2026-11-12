import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import {
  ADMIN,
  adminApi,
  adminData,
  type AdminAccount,
  type AdminPasskey,
  type AdminSessionRecord,
  type Schemas,
} from "./api";
import { useCan } from "./permissions";
import {
  AdminPage,
  Cell,
  Empty,
  QueryView,
  Row,
  Section,
  SmallField,
  Table,
  TableSkeleton,
  Problem,
  SmallSelect,
} from "./ui";
import { makePasskey, passkeysAvailable } from "./webauthn";
import { ListBar } from "./Lists";
import { ListScope, useListQuery, useListView } from "./listView";

const SESSION_COLUMNS = ["Started", "Last used", "From", "Browser", "Admin", ""];
const ADMIN_COLUMNS = ["Admin", "Can sign in", "Roles", ""];

/**
 * An admin's own access (ADR 0051): their passkeys, each named, and their sessions, any of which they can end. An Owner
 * also sees everyone's sessions.
 */
export function Security() {
  const can = useCan();
  return (
    <AdminPage title="Your access">
      <Passkeys />
      <Sessions title="Your sessions" path="/admin/v1/me/sessions" />
      {can("admins.manage") && (
        <ListScope.Provider value="everyone">
          <Sessions title="Everyone's sessions" path="/admin/v1/admins/sessions" />
        </ListScope.Provider>
      )}
      {can("admins.manage") && (
        <ListScope.Provider value="admins">
          <Admins />
        </ListScope.Provider>
      )}
      {can("tags.manage") && <TagVocabulary />}
    </AdminPage>
  );
}

function Passkeys() {
  const client = useQueryClient();
  const key = [ADMIN, "passkeys"];
  const passkeys = useQuery({
    queryKey: key,
    queryFn: () => adminData(adminApi.GET("/admin/v1/passkeys")),
  });
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: key });
    await client.invalidateQueries({ queryKey: [ADMIN, "session"] });
  };
  const add = async () => {
    setBusy(true);
    setError(null);
    try {
      const options = await adminData(adminApi.POST("/admin/v1/passkeys/options"));
      const body = await makePasskey(options, name.trim());
      await adminData(adminApi.POST("/admin/v1/passkeys", { body }));
      setName("");
      await refresh();
    } catch (failed) {
      setError(failed);
    } finally {
      setBusy(false);
    }
  };
  const remove = async (passkey: AdminPasskey) => {
    setError(null);
    try {
      const { response, error: refused } = await adminApi.DELETE("/admin/v1/passkeys/{id}", {
        params: { path: { id: passkey.id } },
      });
      if (!response.ok) {
        throw ApiError.of(response.status, refused);
      }
      await refresh();
    } catch (failed) {
      setError(failed);
    }
  };
  return (
    <Section title="Passkeys">
      <p className="max-w-prose text-small text-text-2">
        A passkey signs you in with your fingerprint, face or device PIN, and can&apos;t be phished. Your
        authenticator app and recovery codes stay as the fallback.
      </p>
      <QueryView
        query={passkeys}
        what="your passkeys"
        skeleton={<TableSkeleton what="your passkeys" rows={2} />}
        isEmpty={(found) => found.passkeys.length === 0}
        empty={<Empty title="No passkey yet">Add one below, on each device you use.</Empty>}
      >
        {(found) => (
          <Table caption="Your passkeys" columns={["Name", "Added", "Last used", ""]}>
            {found.passkeys.map((passkey) => (
              <Row key={passkey.id}>
                <Cell className="font-medium">{passkey.name}</Cell>
                <Cell>
                  <RelativeTime value={passkey.createdAt} />
                </Cell>
                <Cell>{passkey.lastUsedAt ? <RelativeTime value={passkey.lastUsedAt} /> : "never"}</Cell>
                <Cell>
                  <Button variant="quiet" onClick={() => void remove(passkey)}>
                    Remove<span className="sr-only"> {passkey.name}</span>
                  </Button>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      {passkeysAvailable() ? (
        <div className="flex flex-wrap items-end gap-2">
          <SmallField label="Name this passkey" value={name} onChange={setName} placeholder="My phone" />
          <Button
            variant="ghost"
            busy={busy}
            disabled={name.trim() === ""}
            why="Name it first, so you can tell your passkeys apart."
            onClick={() => void add()}
          >
            Add a passkey
          </Button>
        </div>
      ) : (
        <Banner tone="info">
          This browser can&apos;t make a passkey. Your authenticator app still works.
        </Banner>
      )}
      <Problem error={error} />
    </Section>
  );
}

function Sessions({
  title,
  path,
}: {
  title: string;
  path: "/admin/v1/me/sessions" | "/admin/v1/admins/sessions";
}) {
  const client = useQueryClient();
  const view = useListView();
  const sessions = useListQuery([ADMIN, "admin-sessions", path, view], "sessions", (cursor?: string) =>
    path === "/admin/v1/admins/sessions"
      ? adminData(adminApi.GET(path, { params: { query: { ...view, ...(cursor ? { cursor } : {}) } } }))
      : adminData(adminApi.GET(path)),
  );
  const [error, setError] = useState<unknown>(null);
  const end = async (session: AdminSessionRecord) => {
    setError(null);
    const endPath =
      path === "/admin/v1/me/sessions"
        ? "/admin/v1/me/sessions/{id}/end"
        : "/admin/v1/admins/sessions/{id}/end";
    const { response, error: refused } = await adminApi.POST(endPath, {
      params: { path: { id: session.id } },
    });
    if (!response.ok) {
      setError(ApiError.of(response.status, refused));
      return;
    }
    await client.invalidateQueries({ queryKey: [ADMIN, session.current ? "session" : "admin-sessions"] });
  };
  return (
    <Section title={title}>
      {path === "/admin/v1/admins/sessions" && <ListBar list="admin-sessions" tags={false} />}
      <QueryView
        query={sessions}
        what={title.toLowerCase()}
        skeleton={<TableSkeleton what={title.toLowerCase()} rows={2} />}
        isEmpty={(found) => found.sessions.length === 0}
        empty={<Empty title="No live session">Nobody is signed in.</Empty>}
      >
        {(found) => (
          <Table
            caption={title}
            sorts={path === "/admin/v1/admins/sessions" ? { Started: "startedAt" } : {}}
            columns={SESSION_COLUMNS}
          >
            {found.sessions.map((session) => (
              <Row key={session.id}>
                <Cell>
                  <RelativeTime value={session.startedAt} />
                  {session.current && <span className="block text-caption text-text-2">This one</span>}
                </Cell>
                <Cell>{session.lastUsedAt ? <RelativeTime value={session.lastUsedAt} /> : "—"}</Cell>
                <Cell className="tabular">{session.ip ?? "—"}</Cell>
                <Cell className="max-w-64 text-caption break-all">{session.userAgent ?? "—"}</Cell>
                <Cell className="tabular text-caption break-all">{session.userId}</Cell>
                <Cell>
                  <Button variant="quiet" onClick={() => void end(session)}>
                    End
                  </Button>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      <Problem error={error} />
    </Section>
  );
}

type RoleChange = { admin: AdminAccount; role: string; give: boolean };

/** Who holds which role, for an Owner. Changing one goes to another admin, and ends that admin's sessions when it runs. */
function Admins() {
  const view = useListView();
  const admins = useListQuery([ADMIN, "admins", view], "admins", (cursor?: string) =>
    adminData(
      adminApi.GET("/admin/v1/admins", { params: { query: { ...view, ...(cursor ? { cursor } : {}) } } }),
    ),
  );
  const [changing, setChanging] = useState<RoleChange | null>(null);
  return (
    <Section title="Admins and their roles">
      <ListBar list="admins" tags={false} />
      <QueryView query={admins} what="the admins" skeleton={<TableSkeleton what="the admins" rows={2} />}>
        {(found) => (
          <>
            <Table caption="Admins" list="admins" sorts={{ Admin: "email" }} columns={ADMIN_COLUMNS}>
              {found.admins.map((admin) => (
                <Row key={admin.id}>
                  <Cell>
                    {admin.email ?? "—"}
                    <span className="tabular block text-caption text-text-2 break-all">{admin.id}</span>
                  </Cell>
                  <Cell>{admin.locked ? "Locked" : admin.enrolled ? "Yes" : "Not set up yet"}</Cell>
                  <Cell>
                    <div className="flex flex-wrap gap-2">
                      {admin.roles.map((role) => (
                        <Button
                          key={role}
                          variant="quiet"
                          onClick={() => setChanging({ admin, role, give: false })}
                        >
                          Remove {role}
                        </Button>
                      ))}
                    </div>
                  </Cell>
                  <Cell>
                    <div className="flex flex-wrap gap-2">
                      {found.roles
                        .filter((role) => !admin.roles.includes(role.id))
                        .map((role) => (
                          <Button
                            key={role.id}
                            variant="quiet"
                            onClick={() => setChanging({ admin, role: role.id, give: true })}
                          >
                            Give {role.name}
                          </Button>
                        ))}
                    </div>
                  </Cell>
                </Row>
              ))}
            </Table>
            <ActionSheet
              open={changing !== null}
              onOpenChange={(next) => !next && setChanging(null)}
              title={
                changing?.give
                  ? `Give the ${changing.role} role?`
                  : `Remove the ${changing?.role ?? ""} role?`
              }
              change="Another admin approves this. When it runs, this admin's sessions end, so their next one starts with what they may do then."
              confirm={changing?.give ? "Ask to give it" : "Ask to remove it"}
              destructive={changing?.give === false}
              record={changing?.admin}
              typed={changing?.admin.id.slice(0, 8)}
              onConfirm={async (reason) => {
                if (!changing) {
                  return;
                }
                await adminData(
                  adminApi.POST(
                    changing.give
                      ? "/admin/v1/admins/{id}/roles/{role}/grant"
                      : "/admin/v1/admins/{id}/roles/{role}/remove",
                    {
                      params: { path: { id: changing.admin.id, role: changing.role } },
                      body: { reason },
                    },
                  ),
                );
              }}
            />
          </>
        )}
      </QueryView>
    </Section>
  );
}

/** The tags admins can put on a record: the Owner's vocabulary, each with a name and a tone. */
function TagVocabulary() {
  const client = useQueryClient();
  const [form, setForm] = useState<Schemas["TagDefinition"]>({ id: "", label: "", colour: "plain" });
  const [problem, setProblem] = useState<unknown>(null);
  const tags = useQuery({
    queryKey: [ADMIN, "tags"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/tags")),
  });
  const add = async () => {
    setProblem(null);
    try {
      await adminData(adminApi.POST("/admin/v1/tags", { body: form }));
      setForm({ id: "", label: "", colour: "plain" });
      await client.invalidateQueries({ queryKey: [ADMIN, "tags"] });
    } catch (failed) {
      setProblem(failed);
    }
  };
  return (
    <Section title="Tags">
      <p className="text-small text-text-2">
        {tags.data?.tags.map((tag) => tag.label).join(", ") || "No tags yet."}
      </p>
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <SmallField label="Its short name" value={form.id} onChange={(id) => setForm({ ...form, id })} />
        <SmallField
          label="What it says"
          value={form.label}
          onChange={(label) => setForm({ ...form, label })}
        />
        <SmallSelect
          label="Tone"
          value={form.colour}
          onChange={(colour) => setForm({ ...form, colour: colour as Schemas["TagDefinition"]["colour"] })}
          options={[
            ["plain", "Plain"],
            ["running", "Good"],
            ["danger", "Needs care"],
          ]}
        />
        <Button type="submit" variant="ghost">
          Add or change the tag
        </Button>
      </form>
      <Problem error={problem} />
    </Section>
  );
}
