import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type ReactNode } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData, asAction, type AdminNote } from "./api";
import { useCan } from "./permissions";
import { Empty, Id, Pill, QueryView, Section, Problem } from "./ui";

export type NoteSubject = "user" | "host" | "session" | "payment";

/** Markdown-lite: **bold**, *italic*, `code` and new lines. Everything else is text: nothing a note says becomes HTML. */
export function NoteBody({ body }: { body: string }) {
  const pieces: ReactNode[] = [];
  const marks = /\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`/g;
  body.split("\n").forEach((line, row) => {
    if (row > 0) {
      pieces.push(<br key={`break-${row}`} />);
    }
    let from = 0;
    for (const found of line.matchAll(marks)) {
      pieces.push(line.slice(from, found.index));
      const key = `${row}-${found.index}`;
      if (found[1] !== undefined) {
        pieces.push(<strong key={key}>{found[1]}</strong>);
      } else if (found[2] !== undefined) {
        pieces.push(<em key={key}>{found[2]}</em>);
      } else {
        pieces.push(
          <code key={key} className="tabular rounded-control bg-raised px-1">
            {found[3]}
          </code>,
        );
      }
      from = found.index + found[0].length;
    }
    pieces.push(line.slice(from));
  });
  return <p className="text-small">{pieces}</p>;
}

const notesKey = (subject: NoteSubject, id: string) => [ADMIN, "notes", subject, id];

function useNotes(subject: NoteSubject, id: string, enabled: boolean) {
  return useQuery({
    queryKey: notesKey(subject, id),
    queryFn: () => adminData(adminApi.GET("/admin/v1/notes", { params: { query: { subject, id } } })),
    enabled,
  });
}

/** The top of a record's page: its pinned notes as banners, and its tags, which an admin who may write can change. */
export function RecordHead({ subject, id }: { subject: NoteSubject; id: string }) {
  const can = useCan();
  const readable = can("notes.read");
  const notes = useNotes(subject, id, readable);
  if (!readable) {
    return null;
  }
  const pinned = (notes.data?.notes ?? []).filter((note) => note.pinned);
  return (
    <div className="flex flex-col gap-2">
      {pinned.map((note) => (
        <Banner key={note.id} tone="warning">
          <NoteBody body={note.body} />
        </Banner>
      ))}
      <Tags subject={subject} id={id} />
    </div>
  );
}

const toneOf = (colour: string) =>
  colour === "danger" ? "danger" : colour === "running" ? "running" : "plain";

function Tags({ subject, id }: { subject: NoteSubject; id: string }) {
  const can = useCan();
  const client = useQueryClient();
  const writable = can("notes.write");
  const [problem, setProblem] = useState<unknown>(null);
  const on = useQuery({
    queryKey: [ADMIN, "record-tags", subject, id],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/records/{subject}/{id}/tags", { params: { path: { subject, id } } })),
  });
  const vocabulary = useQuery({
    queryKey: [ADMIN, "tags"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/tags")),
    enabled: writable,
    staleTime: 60_000,
  });
  const change = async (run: () => Promise<unknown>) => {
    setProblem(null);
    try {
      await run();
      await client.invalidateQueries({ queryKey: [ADMIN, "record-tags", subject, id] });
    } catch (failed) {
      setProblem(failed);
    }
  };
  const held = on.data?.tags ?? [];
  const others = (vocabulary.data?.tags ?? []).filter((tag) => !held.some((mine) => mine.id === tag.id));
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Tags">
        {held.map((tag) => (
          <span key={tag.id} className="inline-flex items-center gap-1">
            <Pill tone={toneOf(tag.colour)}>{tag.label}</Pill>
            {writable && (
              <Button
                variant="quiet"
                aria-label={`Remove the tag ${tag.label}`}
                onClick={() =>
                  void change(() =>
                    adminData(
                      adminApi.POST("/admin/v1/records/{subject}/{id}/tags/{tag}/remove", {
                        params: { path: { subject, id, tag: tag.id } },
                      }),
                    ),
                  )
                }
              >
                ×
              </Button>
            )}
          </span>
        ))}
        {writable && others.length > 0 && (
          <label className="flex items-center gap-2 text-caption text-text-2">
            Add a tag
            <select
              value=""
              onChange={(event) => {
                const tag = event.target.value;
                if (tag !== "") {
                  void change(() =>
                    adminData(
                      adminApi.POST("/admin/v1/records/{subject}/{id}/tags/{tag}", {
                        params: { path: { subject, id, tag } },
                      }),
                    ),
                  );
                }
              }}
              className="min-h-touch rounded-control bg-raised px-2 text-small text-text md:min-h-9"
            >
              <option value="">Choose…</option>
              {others.map((tag) => (
                <option key={tag.id} value={tag.id}>
                  {tag.label}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      <Problem error={problem} />
    </div>
  );
}

/** One note, with what its reader may do to it. */
function NoteRow({ note, subject, id }: { note: AdminNote; subject: NoteSubject; id: string }) {
  const can = useCan();
  const client = useQueryClient();
  const [editing, setEditing] = useState<string | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const key = useRef(crypto.randomUUID());
  const writable = can("notes.write");
  const act = async (run: () => Promise<unknown>) => {
    setProblem(null);
    try {
      await asAction(key.current, note, run);
      key.current = crypto.randomUUID();
      setEditing(null);
      await client.invalidateQueries({ queryKey: notesKey(subject, id) });
    } catch (failed) {
      setProblem(failed);
    }
  };
  const path = { params: { path: { id: note.id } } };
  return (
    <li className="flex flex-col gap-2 border-b border-line py-3">
      <div className="flex flex-wrap items-center gap-2 text-caption text-text-2">
        <Id value={note.authorId} page="user" /> · <RelativeTime value={note.createdAt} />
        {note.editedAt && <span>· changed</span>}
        {note.pinned && <Pill>Pinned</Pill>}
        {note.openInvestigation && <Pill tone="danger">Open investigation</Pill>}
      </div>
      {editing === null ? (
        <NoteBody body={note.body} />
      ) : (
        <textarea
          aria-label="The note"
          value={editing}
          maxLength={4000}
          rows={3}
          onChange={(event) => setEditing(event.target.value)}
          className="rounded-control bg-raised px-3 py-2 text-small text-text"
        />
      )}
      <Problem error={problem} />
      {writable && (
        <div className="flex flex-wrap gap-1">
          {note.editable &&
            (editing === null ? (
              <Button variant="quiet" onClick={() => setEditing(note.body)}>
                Change
              </Button>
            ) : (
              <Button
                variant="quiet"
                onClick={() =>
                  void act(() =>
                    adminData(
                      adminApi.POST("/admin/v1/notes/{id}/edit", { ...path, body: { body: editing } }),
                    ),
                  )
                }
              >
                Save
              </Button>
            ))}
          <Button
            variant="quiet"
            onClick={() =>
              void act(() =>
                adminData(
                  adminApi.POST("/admin/v1/notes/{id}/change", { ...path, body: { pinned: !note.pinned } }),
                ),
              )
            }
          >
            {note.pinned ? "Unpin" : "Pin"}
          </Button>
          {note.openInvestigation && (
            <Button
              variant="quiet"
              onClick={() =>
                void act(() =>
                  adminData(
                    adminApi.POST("/admin/v1/notes/{id}/change", {
                      ...path,
                      body: { openInvestigation: false },
                    }),
                  ),
                )
              }
            >
              Investigation closed
            </Button>
          )}
        </div>
      )}
    </li>
  );
}

/** The notes on a record, and a new one (ADR 0052): audited, and emailed only as a link to those it mentions. */
export function RecordNotes({ subject, id }: { subject: NoteSubject; id: string }) {
  const can = useCan();
  const client = useQueryClient();
  const notes = useNotes(subject, id, true);
  const writable = can("notes.write");
  const mentionable = useQuery({
    queryKey: [ADMIN, "mentionable"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/notes/mentionable")),
    enabled: writable,
    staleTime: 60_000,
  });
  const [body, setBody] = useState("");
  const [pinned, setPinned] = useState(false);
  const [investigation, setInvestigation] = useState(false);
  const [mentions, setMentions] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const key = useRef(crypto.randomUUID());
  const add = async () => {
    if (body.trim() === "") {
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await asAction(key.current, undefined, () =>
        adminData(
          adminApi.POST("/admin/v1/notes", {
            body: { subject, id, body, pinned, openInvestigation: investigation, mentions },
          }),
        ),
      );
      key.current = crypto.randomUUID();
      setBody("");
      setPinned(false);
      setInvestigation(false);
      setMentions([]);
      await client.invalidateQueries({ queryKey: notesKey(subject, id) });
    } catch (failed) {
      setProblem(failed);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex flex-col gap-4">
      {writable && (
        <Section title="Add a note">
          <form
            className="flex flex-col gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              void add();
            }}
          >
            <textarea
              aria-label="The note"
              value={body}
              maxLength={4000}
              rows={3}
              onChange={(event) => setBody(event.target.value)}
              className="rounded-control bg-raised px-3 py-2 text-small text-text"
            />
            <div className="flex flex-wrap items-center gap-4 text-small">
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={pinned}
                  onChange={(event) => setPinned(event.target.checked)}
                />
                Pin it to the top
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={investigation}
                  onChange={(event) => setInvestigation(event.target.checked)}
                />
                For an open fraud investigation
              </label>
              <label className="flex items-center gap-2">
                Mention
                <select
                  value=""
                  onChange={(event) => {
                    const who = event.target.value;
                    if (who !== "" && !mentions.includes(who)) {
                      setMentions([...mentions, who]);
                    }
                  }}
                  className="min-h-touch rounded-control bg-raised px-2 text-small text-text md:min-h-9"
                >
                  <option value="">Someone…</option>
                  {(mentionable.data?.admins ?? []).map((admin) => (
                    <option key={admin.id} value={admin.id}>
                      {admin.name}
                    </option>
                  ))}
                </select>
              </label>
              {mentions.map((who) => (
                <Pill key={who}>
                  @{mentionable.data?.admins.find((admin) => admin.id === who)?.name ?? who.slice(-8)}
                </Pill>
              ))}
            </div>
            <Problem error={problem} />
            <Button type="submit" variant="ghost" busy={busy} className="self-start">
              Add the note
            </Button>
          </form>
        </Section>
      )}
      <QueryView
        query={notes}
        what="the notes"
        isEmpty={(found) => found.notes.length === 0}
        empty={<Empty title="No notes">Anything worth remembering about this goes here.</Empty>}
      >
        {(found) => (
          <ul aria-label="Notes" className="flex flex-col">
            {found.notes.map((note) => (
              <NoteRow key={note.id} note={note} subject={subject} id={id} />
            ))}
          </ul>
        )}
      </QueryView>
    </div>
  );
}

const STRENGTHS: Record<string, string> = { STRONG: "Strong", MEDIUM: "Medium", WEAK: "Weak" };

/** Accounts that may be the same person or ring (ADR 0052): leads for a person to look at. Nothing here acts. */
export function LinkedAccounts({ id }: { id: string }) {
  const linked = useQuery({
    queryKey: [ADMIN, "linked-accounts", id],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/users/{id}/linked-accounts", { params: { path: { id } } })),
  });
  return (
    <QueryView
      query={linked}
      what="linked accounts"
      isEmpty={(found) => found.accounts.length === 0}
      empty={
        <Empty title="None found">
          No other account shares a device, a network, a PAN, a UPI ID or an email.
        </Empty>
      }
    >
      {(found) => (
        <div className="flex flex-col gap-3">
          <p className="text-caption text-text-2">
            These are signals to look at, not findings. Nothing happens to an account because it&apos;s listed
            here.
          </p>
          {found.unanswered > 0 && (
            <p className="text-caption text-text-2">
              Some signals didn&apos;t answer in time, so there may be more.
            </p>
          )}
          <ul aria-label="Linked accounts" className="flex flex-col">
            {found.accounts.map((account) => (
              <li key={account.userId} className="flex flex-col gap-1 border-b border-line py-3">
                <Id value={account.userId} page="user" />
                <ul className="flex flex-col gap-0.5 text-small">
                  {account.signals.map((signal) => (
                    <li
                      key={`${signal.signal}${signal.evidence}`}
                      className="flex flex-wrap items-center gap-2"
                    >
                      <Pill tone={signal.strength === "STRONG" ? "danger" : "plain"}>
                        {STRENGTHS[signal.strength] ?? signal.strength}
                      </Pill>
                      {signal.evidence}
                      {signal.at && (
                        <span className="text-caption text-text-2">
                          · <RelativeTime value={signal.at} />
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </div>
      )}
    </QueryView>
  );
}
