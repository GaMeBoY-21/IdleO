import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { refusal } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type AdminPasskey, type AdminSessionRecord } from "./api";
import { useCan } from "./permissions";
import { AdminPage, Cell, Empty, QueryView, Row, Section, SmallField, Table, TableSkeleton } from "./ui";
import { makePasskey, passkeysAvailable } from "./webauthn";

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
      {can("admins.manage") && <Sessions title="Everyone's sessions" path="/admin/v1/admins/sessions" />}
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
        throw refused as Error;
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
        <div className="flex flex-wrap items-end gap-3">
          <SmallField label="Name this passkey" value={name} onChange={setName} placeholder="My phone" />
          <Button variant="ghost" busy={busy} disabled={name.trim() === ""} onClick={() => void add()}>
            Add a passkey
          </Button>
        </div>
      ) : (
        <Banner tone="info">
          This browser can&apos;t make a passkey. Your authenticator app still works.
        </Banner>
      )}
      {error !== null && <Banner tone="danger">{refusal(error)}</Banner>}
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
  const key = [ADMIN, "admin-sessions", path];
  const sessions = useQuery({ queryKey: key, queryFn: () => adminData(adminApi.GET(path)) });
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
      setError(refused);
      return;
    }
    await client.invalidateQueries({ queryKey: [ADMIN, session.current ? "session" : "admin-sessions"] });
  };
  return (
    <Section title={title}>
      <QueryView
        query={sessions}
        what={title.toLowerCase()}
        skeleton={<TableSkeleton what={title.toLowerCase()} rows={2} />}
        isEmpty={(found) => found.sessions.length === 0}
        empty={<Empty title="No live session">Nobody is signed in.</Empty>}
      >
        {(found) => (
          <Table caption={title} columns={["Started", "Last used", "From", "Browser", "Admin", ""]}>
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
      {error !== null && <Banner tone="danger">{refusal(error)}</Banner>}
    </Section>
  );
}
