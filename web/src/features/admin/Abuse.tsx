import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Inputs";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { problemMessage } from "@/lib/problems";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import {
  ADMIN,
  adminApi,
  adminData,
  type AbuseDetail,
  type AbuseSummary,
  type BlocklistAddition,
} from "./api";
import {
  AdminPage,
  BackLink,
  Cell,
  Empty,
  Fields,
  Id,
  PagedView,
  Pill,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallSelect,
  Table,
} from "./ui";

/** What a signal is, in the admin's words. */
const SIGNALS: Record<string, string> = {
  process_name: "A program with a listed name ran",
  process_hash: "A listed build ran (by its hash)",
  pool_lookup: "The job looked up a mining pool",
  connection_drops: "Its new connections were dropped for 5 minutes running",
  mining_pattern: "Its numbers look like mining for 15 minutes",
};

const ACTIONS: Record<string, string> = {
  none: "Nothing was done by itself",
  stopped: "The runner stopped the session",
  suspended: "The runner stopped the session and the account was suspended",
};

const STATE: Record<string, string> = { open: "Open", confirmed: "Confirmed", cleared: "Cleared" };

/**
 * Abuse (ADR 0038): the sessions a host's runner flagged or stopped for software no renter may run on a host's GPU. Open
 * items first, oldest first. One opens with every signal on it, what the job did each minute and what the session cost;
 * an admin confirms it (the suspension stays, perhaps a ban, which only clearing lifts), clears it (the account is back
 * and the session's charge is refunded) or adds a note. Only a stop ever suspended anyone: a name, a first look-up, dropped
 * connections and a pattern of numbers tell an admin and stop nothing.
 */
export function Abuse() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <AbuseRecord id={id} /> : <AbuseQueue />;
}

function StatePill({ item }: { item: Pick<AbuseSummary, "state" | "banned"> }) {
  return (
    <Pill tone={item.state === "open" ? "danger" : "plain"}>
      {STATE[item.state] ?? item.state}
      {item.banned ? ", banned" : ""}
    </Pill>
  );
}

function AbuseQueue() {
  const [state, setState] = useState("open");
  const items = useInfiniteQuery({
    queryKey: [ADMIN, "abuse", state],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/abuse", {
          params: { query: { state, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Abuse">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["open", "Open"],
            ["confirmed", "Confirmed"],
            ["cleared", "Cleared"],
            ["all", "All"],
          ]}
        />
      </div>
      <PagedView
        query={items}
        what="the abuse reviews"
        items={(page) => page.items}
        empty={
          <Empty title="Nothing to look at">
            Sessions a host&apos;s runner flags or stops for software that isn&apos;t allowed show here,
            oldest first.
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Abuse reviews"
            columns={["Review", "State", "What was seen", "Renter", "Host", "Since", ""]}
          >
            {rows.map((item) => (
              <Row key={item.id}>
                <Cell>
                  <Id value={item.id} />
                </Cell>
                <Cell>
                  <StatePill item={item} />
                </Cell>
                <Cell>
                  {item.kinds.map((kind) => SIGNALS[kind] ?? kind).join("; ")}
                  {item.action !== "none" && (
                    <span className="block text-text-muted">{ACTIONS[item.action]}</span>
                  )}
                </Cell>
                <Cell>
                  <Id value={item.renterId} />
                </Cell>
                <Cell>{item.hostId ? <Id value={item.hostId} /> : "—"}</Cell>
                <Cell>
                  <RelativeTime value={item.createdAt} />
                </Cell>
                <Cell>
                  <RowLink href={`/admin/abuse/?id=${item.id}`}>
                    Open<span className="sr-only"> review {item.id}</span>
                  </RowLink>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      <WaitingBuilds />
    </AdminPage>
  );
}

type Decision = "confirm" | "ban" | "clear" | null;

/** What a listed program is for, as the blocklist file says it. */
const KINDS: [string, string][] = [
  ["miner", "A cryptocurrency miner"],
  ["password_cracker", "A password cracker"],
];

/**
 * The builds of flagged programs that a confirmed review saw, each with "Add to the blocklist": the runner worked out the
 * executable's SHA-256 when the program's name was listed, so an admin who confirms the session can list that exact build
 * and stop it by hash under any name (ADR 0038, ADR 0041).
 */
function LearnableBuilds({ found, itemId }: { found: AbuseDetail; itemId: string }) {
  const client = useQueryClient();
  const additions = useQuery({
    queryKey: [ADMIN, "blocklist-additions"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/abuse/blocklist-additions")),
  });
  const [adding, setAdding] = useState<{ sha256: string; name: string } | null>(null);
  const [kind, setKind] = useState("miner");
  if (found.item.state !== "confirmed") {
    return null;
  }
  const builds = found.signals.filter((signal) => signal.kind === "process_name" && signal.sha256 !== "");
  if (builds.length === 0) {
    return (
      <Section title="Blocklist">
        <Empty title="No build to add">
          The runner couldn&apos;t read the executable of the program it flagged, so there&apos;s no hash to
          list.
        </Empty>
      </Section>
    );
  }
  const waiting = new Set(additions.data?.additions.map((entry) => entry.sha256));
  return (
    <Section title="Blocklist">
      <ul className="flex flex-col gap-3 text-small">
        {builds.map((signal) => (
          <li key={signal.sha256} className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="font-medium">{signal.detail}</span>
            <span className="tabular break-all text-text-muted">{signal.sha256}</span>
            {waiting.has(signal.sha256) ? (
              <Pill tone="plain">On the list to publish</Pill>
            ) : (
              <Button
                variant="secondary"
                onClick={() => setAdding({ sha256: signal.sha256, name: signal.detail })}
              >
                Add this program&apos;s hash to the blocklist
              </Button>
            )}
          </li>
        ))}
      </ul>
      {adding !== null && (
        <ActionSheet
          open
          onOpenChange={(next) => !next && setAdding(null)}
          title={`Add ${adding.name}'s build to the blocklist?`}
          change={`From the next signed blocklist, a session whose program has this exact build (${adding.sha256.slice(0, 12)}…) is stopped, whatever it is called. The file is signed offline with the release key and published by an operator; until then it is waiting.`}
          confirm="Add to the blocklist"
          extra={<SmallSelect label="What it is" value={kind} onChange={setKind} options={KINDS} />}
          onConfirm={async (reason) => {
            await adminData(
              adminApi.POST("/admin/v1/abuse/{id}/blocklist", {
                params: { path: { id: itemId } },
                body: {
                  sha256: adding.sha256,
                  kind: kind === "password_cracker" ? "password_cracker" : "miner",
                  reason,
                },
              }),
            );
            await client.invalidateQueries({ queryKey: [ADMIN] });
          }}
        />
      )}
    </Section>
  );
}

/** Builds waiting to go on the signed blocklist, and which the published list already names; each can be taken back. */
function WaitingBuilds() {
  const client = useQueryClient();
  const additions = useQuery({
    queryKey: [ADMIN, "blocklist-additions"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/abuse/blocklist-additions")),
  });
  const [removing, setRemoving] = useState<BlocklistAddition | null>(null);
  return (
    <Section title="Blocklist additions">
      <QueryView
        query={additions}
        what="the blocklist additions"
        isEmpty={(data) => data.additions.length === 0}
        empty={
          <Empty title="Nothing waiting">
            A build you add from a confirmed review shows here until the signed blocklist names it.
          </Empty>
        }
      >
        {(data) => (
          <Table
            caption="Blocklist additions"
            columns={["Program", "Build", "What it is", "Reason", "Added", "State", ""]}
          >
            {data.additions.map((entry) => (
              <Row key={entry.id}>
                <Cell>{entry.name}</Cell>
                <Cell className="tabular break-all">{entry.sha256.slice(0, 16)}…</Cell>
                <Cell>{KINDS.find(([value]) => value === entry.kind)?.[1] ?? entry.kind}</Cell>
                <Cell>{entry.reason}</Cell>
                <Cell>
                  <RelativeTime value={entry.createdAt} />
                </Cell>
                <Cell>{entry.published ? "In the published list" : "Waiting to be published"}</Cell>
                <Cell>
                  <Button variant="secondary" onClick={() => setRemoving(entry)}>
                    Take back
                    <span className="sr-only"> {entry.name}</span>
                  </Button>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      {removing !== null && (
        <ActionSheet
          open
          onOpenChange={(next) => !next && setRemoving(null)}
          title={`Take ${removing.name}'s build off the blocklist?`}
          change={
            removing.published
              ? "It is in the published list. The next signed list leaves it out once an operator publishes again, and every agent takes that list like any newer one."
              : "It is not published yet: it is simply left out of the next signed list."
          }
          confirm="Take it back"
          destructive
          onConfirm={async (reason) => {
            await adminData(
              adminApi.POST("/admin/v1/abuse/blocklist-additions/{additionId}/remove", {
                params: { path: { additionId: removing.id } },
                body: { reason },
              }),
            );
            await client.invalidateQueries({ queryKey: [ADMIN] });
          }}
        />
      )}
    </Section>
  );
}

function AbuseRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const detail = useQuery({
    queryKey: [ADMIN, "abuse-item", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/abuse/{id}", { params: { path: { id } } })),
  });
  const [deciding, setDeciding] = useState<Decision>(null);
  const [note, setNote] = useState("");
  const [noteError, setNoteError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <AdminPage title="Abuse review">
      <BackLink href="/admin/abuse/">← All reviews</BackLink>
      <QueryView query={detail} what="this review">
        {(found) => (
          <>
            <Summary found={found} />
            {found.item.state !== "cleared" && (
              <div className="flex flex-wrap gap-2">
                {found.item.state === "open" && (
                  <Button variant="secondary" onClick={() => setDeciding("confirm")}>
                    Confirm
                  </Button>
                )}
                {!found.item.banned && (
                  <Button variant="secondary" onClick={() => setDeciding("ban")}>
                    Confirm and ban
                  </Button>
                )}
                <Button variant="primary" onClick={() => setDeciding("clear")}>
                  Clear and refund
                </Button>
              </div>
            )}
            <ActionSheet
              open={deciding === "confirm" || deciding === "ban"}
              onOpenChange={(next) => !next && setDeciding(null)}
              title={deciding === "ban" ? "Confirm and ban this account?" : "Confirm this review?"}
              change={
                deciding === "ban"
                  ? "The account is banned: refused like a suspension, and lifted only by clearing this review. The person is told the same either way, and can still see their data, delete the account and write to us."
                  : "The suspension stays. Nothing else changes; you can ban the account later."
              }
              confirm={deciding === "ban" ? "Confirm and ban" : "Confirm"}
              destructive={deciding === "ban"}
              onConfirm={async (reason) => {
                await adminData(
                  adminApi.POST("/admin/v1/abuse/{id}/confirm", {
                    params: { path: { id } },
                    body: { ban: deciding === "ban", reason },
                  }),
                );
                await refresh();
              }}
            />
            <ActionSheet
              open={deciding === "clear"}
              onOpenChange={(next) => !next && setDeciding(null)}
              title="Clear this review and refund the session?"
              change={
                <>
                  The account is reinstated (a ban lifts here and only here), and the{" "}
                  <Money paise={found.paidPaise} /> the session cost is refunded to the renter&apos;s credit,
                  from the platform&apos;s commission first and then the host&apos;s share. The host is not
                  refunded anything they weren&apos;t charged: the refund is in one ledger transaction.
                </>
              }
              confirm="Clear and refund"
              onConfirm={async (reason) => {
                await adminData(
                  adminApi.POST("/admin/v1/abuse/{id}/clear", { params: { path: { id } }, body: { reason } }),
                );
                await refresh();
              }}
            />
            <LearnableBuilds found={found} itemId={id} />
            <Section title="Add a note">
              <form
                noValidate
                className="flex flex-col gap-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (note.trim().length < 5) {
                    setNoteError("Write at least 5 characters.");
                    return;
                  }
                  setSaving(true);
                  setNoteError(null);
                  void adminData(
                    adminApi.POST("/admin/v1/abuse/{id}/notes", {
                      params: { path: { id } },
                      body: { note: note.trim() },
                    }),
                  )
                    .then(() => {
                      setNote("");
                      return refresh();
                    })
                    .catch((error: unknown) => setNoteError(problemMessage(error)))
                    .finally(() => setSaving(false));
                }}
              >
                <TextArea
                  label="Note"
                  rows={3}
                  maxLength={500}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  error={noteError ?? undefined}
                  hint="The note is its own reason in the audit log. No email or address."
                />
                <Button type="submit" variant="secondary" busy={saving} className="self-start">
                  Add the note
                </Button>
              </form>
            </Section>
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}

function Summary({ found }: { found: AbuseDetail }) {
  const { item } = found;
  return (
    <>
      <Fields
        items={[
          ["State", <StatePill key="state" item={item} />],
          ["Done by itself", ACTIONS[item.action] ?? item.action],
          [
            "Renter",
            <Link
              key="renter"
              href={`/admin/users/?id=${item.renterId}`}
              className="tabular underline-offset-4 hover:underline"
            >
              {item.renterId}
            </Link>,
          ],
          [
            "Host",
            item.hostId ? (
              <Link
                key="host"
                href={`/admin/hosts/?id=${item.hostId}`}
                className="tabular underline-offset-4 hover:underline"
              >
                {item.hostId}
              </Link>
            ) : (
              "—"
            ),
          ],
          [
            "Session",
            <Link
              key="session"
              href={`/admin/sessions/?id=${item.sessionId}`}
              className="tabular underline-offset-4 hover:underline"
            >
              {item.sessionId}
            </Link>,
          ],
          ["What the renter paid", <Money key="paid" paise={found.paidPaise} />],
          [
            "Refunded by clearing",
            found.refundedPaise == null ? "—" : <Money key="refunded" paise={found.refundedPaise} />,
          ],
          [
            "Settled",
            item.resolvedAt ? `${formatDateTime(item.resolvedAt)}: ${item.resolution ?? ""}` : "Not yet",
          ],
        ]}
      />
      <Section title="What was seen">
        <ol className="flex flex-col gap-2 text-small">
          {found.signals.map((signal, index) => (
            <li key={`${signal.at}-${index}`} className="flex flex-wrap gap-x-3">
              <time dateTime={signal.at} className="tabular text-text-muted">
                {formatDateTime(signal.at)}
              </time>
              <span className="font-medium">{SIGNALS[signal.kind] ?? signal.kind}</span>
              <span className="break-all text-text-muted">
                {signal.detail}
                {signal.sha256 ? ` (${signal.sha256})` : ""}
                {signal.count > 0 ? ` (${signal.count})` : ""}
                {signal.stopped ? " — the runner stopped the session" : ""}
              </span>
            </li>
          ))}
        </ol>
      </Section>
      {found.behaviour.length > 0 && (
        <Section title="What the job did each minute">
          <Table
            caption="Behaviour"
            columns={[
              "Minute",
              "GPU %",
              "GPU memory %",
              "PCIe KB/s",
              "Sent",
              "Received",
              "Lines printed",
              "Dropped",
            ]}
          >
            {found.behaviour.map((minute) => (
              <Row key={minute.minuteIndex}>
                <Cell>{minute.minuteIndex}</Cell>
                <Cell>{minute.gpuPercent}</Cell>
                <Cell>{minute.memoryPercent}</Cell>
                <Cell>{minute.pcieKbPerSecond}</Cell>
                <Cell>{minute.networkOutBytes}</Cell>
                <Cell>{minute.networkInBytes}</Cell>
                <Cell>{minute.printedLines}</Cell>
                <Cell>{minute.connectionsDropped}</Cell>
              </Row>
            ))}
          </Table>
          <p className="text-caption text-text-muted">
            The last 60 minutes. A miner works the GPU hard with little of its memory, next to nothing over
            the bus and a small steady trickle over the network; training and rendering don&apos;t look like
            that. These numbers only ever tell you to look: they stop no one.
          </p>
        </Section>
      )}
      {found.notes.length > 0 && (
        <Section title="Notes">
          <ol className="flex flex-col gap-3">
            {found.notes.map((note, index) => (
              <li key={`${note.at}-${index}`} className="flex flex-col gap-1 rounded-control bg-ground p-4">
                <p className="text-caption text-text-muted">{formatDateTime(note.at)}</p>
                <p className="whitespace-pre-wrap break-words text-body">{note.note || "(erased)"}</p>
              </li>
            ))}
          </ol>
        </Section>
      )}
    </>
  );
}
