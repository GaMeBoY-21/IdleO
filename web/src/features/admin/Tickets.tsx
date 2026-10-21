import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearch } from "wouter";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Inputs";
import { RelativeTime } from "@/components/ui/Time";
import { problemMessage } from "@/lib/problems";
import { ticketNumber } from "@/lib/support";
import { formatDateTime, formatDay } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
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

/** The topics, as the server lists them (Tickets.TOPICS), for the filter. */
const TOPICS: [string, string][] = [
  ["", "Every topic"],
  ["privacy", "Privacy requests"],
  ["grievance", "Grievances"],
  ["session", "Sessions"],
  ["money", "Money and credit"],
  ["hosting", "Hosting"],
  ["account", "Accounts"],
  ["report", "Reports about someone"],
];

const STATUS: Record<string, string> = {
  open: "Open",
  waiting: "Waiting on the person",
  resolved: "Resolved",
};

/**
 * Tickets (ADR 0037): messages to support, open ones first, by due date with the overdue highlighted. One opens with
 * its messages, the person and the session or machine it names; an answer is emailed with its number in the subject,
 * and a privacy request can start the person's export or show where their deletion stands. Every action is audited.
 */
export function Tickets() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <TicketRecord id={id} /> : <TicketQueue />;
}

function TicketQueue() {
  const [topic, setTopic] = useState("");
  const [status, setStatus] = useState("open");
  const [age, setAge] = useState("");
  const tickets = useInfiniteQuery({
    queryKey: [ADMIN, "tickets", topic, status, age],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/tickets", {
          params: {
            query: {
              ...(topic ? { topic } : {}),
              ...(status ? { status } : {}),
              ...(age ? { olderThanDays: Number(age) } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Tickets">
      <div className="flex flex-wrap gap-3">
        <SmallSelect label="Topic" value={topic} onChange={setTopic} options={TOPICS} />
        <SmallSelect
          label="Status"
          value={status}
          onChange={setStatus}
          options={[
            ["open", "Open"],
            ["waiting", "Waiting on the person"],
            ["resolved", "Resolved"],
            ["", "All"],
          ]}
        />
        <SmallSelect
          label="Age"
          value={age}
          onChange={setAge}
          options={[
            ["", "Any age"],
            ["2", "Older than 2 days"],
            ["7", "Older than a week"],
            ["30", "Older than a month"],
          ]}
        />
      </div>
      <PagedView
        query={tickets}
        what="tickets"
        items={(page) => page.tickets}
        empty={<Empty title="No tickets here">Messages from the Contact page and Help show here.</Empty>}
      >
        {(rows) => (
          <Table caption="Tickets" columns={["Ticket", "Topic", "Status", "Due", "Arrived"]}>
            {rows.map((ticket) => (
              <Row key={ticket.id} className={ticket.overdue ? "bg-danger-soft" : undefined}>
                <Cell>
                  <RowLink href={`/admin/tickets/?id=${ticket.id}`}>{ticketNumber(ticket.number)}</RowLink>
                </Cell>
                <Cell>{TOPICS.find(([code]) => code === ticket.topic)?.[1] ?? ticket.topic}</Cell>
                <Cell>{STATUS[ticket.status] ?? ticket.status}</Cell>
                <Cell>
                  {ticket.dueAt ? (
                    ticket.overdue ? (
                      <Pill tone="danger">Overdue since {formatDay(ticket.dueAt)}</Pill>
                    ) : (
                      formatDay(ticket.dueAt)
                    )
                  ) : (
                    "—"
                  )}
                </Cell>
                <Cell>
                  <RelativeTime value={ticket.createdAt} />
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
    </AdminPage>
  );
}

function TicketRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const thread = useQuery({
    queryKey: [ADMIN, "ticket", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/tickets/{id}", { params: { path: { id } } })),
  });
  const [answer, setAnswer] = useState("");
  const [after, setAfter] = useState("waiting");
  const [answerError, setAnswerError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [changing, setChanging] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN, "ticket", id] });

  return (
    <AdminPage title="Ticket">
      <BackLink href="/admin/tickets/">All tickets</BackLink>
      <QueryView query={thread} what="this ticket">
        {({ ticket, messages }) => (
          <>
            <Section
              title={`${ticketNumber(ticket.number)} · ${TOPICS.find(([code]) => code === ticket.topic)?.[1] ?? ticket.topic}`}
            >
              <Fields
                items={[
                  ["Status", STATUS[ticket.status] ?? ticket.status],
                  ["Answer to", ticket.replyTo ?? "Erased"],
                  [
                    "Person",
                    ticket.userId ? (
                      <RowLink href={`/admin/users/?id=${ticket.userId}`}>
                        <Id value={ticket.userId} />
                      </RowLink>
                    ) : (
                      "Not signed in"
                    ),
                  ],
                  [
                    "Session",
                    ticket.sessionId ? (
                      <RowLink href={`/admin/sessions/?id=${ticket.sessionId}`}>
                        <Id value={ticket.sessionId} />
                      </RowLink>
                    ) : (
                      "—"
                    ),
                  ],
                  [
                    "Machine",
                    ticket.hostId ? (
                      <RowLink href={`/admin/hosts/?id=${ticket.hostId}`}>
                        <Id value={ticket.hostId} />
                      </RowLink>
                    ) : (
                      "—"
                    ),
                  ],
                  [
                    "Due",
                    ticket.dueAt ? (
                      ticket.overdue ? (
                        <Pill tone="danger">Overdue since {formatDay(ticket.dueAt)}</Pill>
                      ) : (
                        formatDay(ticket.dueAt)
                      )
                    ) : (
                      "No due date"
                    ),
                  ],
                ]}
              />
              <div className="flex flex-wrap gap-3">
                {["open", "waiting", "resolved"]
                  .filter((status) => status !== ticket.status)
                  .map((status) => (
                    <Button key={status} variant="secondary" onClick={() => setChanging(status)}>
                      Mark {STATUS[status]?.toLowerCase()}
                    </Button>
                  ))}
              </div>
            </Section>
            {(ticket.topic === "privacy" || ticket.topic === "grievance") && ticket.userId && (
              <PrivacyRequest userId={ticket.userId} onExport={() => setExporting(true)} />
            )}
            <Section title="Messages">
              <ol className="flex flex-col gap-3">
                {messages.map((message) => (
                  <li
                    key={`${message.createdAt}${message.author}`}
                    className={`flex flex-col gap-1 rounded-control p-4 ${message.author === "admin" ? "bg-ground" : "bg-fill"}`}
                  >
                    <p className="text-caption text-text-muted">
                      {message.author === "admin" ? "We wrote" : "They wrote"},{" "}
                      {formatDateTime(message.createdAt)}
                    </p>
                    <p className="whitespace-pre-wrap break-words text-body">{message.body || "(erased)"}</p>
                  </li>
                ))}
              </ol>
            </Section>
            <Section title="Answer">
              <form
                noValidate
                className="flex flex-col gap-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (answer.trim() === "") {
                    setAnswerError("Write the answer.");
                    return;
                  }
                  setSending(true);
                  setAnswerError(null);
                  void adminData(
                    adminApi.POST("/admin/v1/tickets/{id}/answer", {
                      params: { path: { id } },
                      body: { message: answer.trim(), status: after },
                    }),
                  )
                    .then(() => {
                      setAnswer("");
                      return refresh();
                    })
                    .catch((error: unknown) => setAnswerError(problemMessage(error)))
                    .finally(() => setSending(false));
                }}
              >
                <TextArea
                  label={`Emailed to ${ticket.replyTo ?? "nobody (erased)"} as ${ticketNumber(ticket.number)}`}
                  rows={6}
                  maxLength={5000}
                  value={answer}
                  onChange={(event) => setAnswer(event.target.value)}
                  error={answerError ?? undefined}
                  hint="The person reads exactly this. The audit log records that it was answered, not the words."
                />
                <SmallSelect
                  label="Then"
                  value={after}
                  onChange={setAfter}
                  options={[
                    ["waiting", "Waiting on the person"],
                    ["resolved", "Resolved"],
                    ["open", "Still open"],
                  ]}
                />
                <Button type="submit" busy={sending} className="self-start" disabled={!ticket.replyTo}>
                  Send the answer
                </Button>
              </form>
            </Section>
            <ActionSheet
              open={changing !== null}
              onOpenChange={(open) => !open && setChanging(null)}
              title="Change the status"
              change={`${ticketNumber(ticket.number)} becomes "${STATUS[changing ?? ""] ?? ""}". The person isn't emailed.`}
              confirm="Change it"
              onConfirm={async (reason) => {
                await adminData(
                  adminApi.POST("/admin/v1/tickets/{id}/status", {
                    params: { path: { id } },
                    body: { status: changing ?? "open", reason },
                  }),
                );
                await refresh();
              }}
            />
            {ticket.userId && (
              <ActionSheet
                open={exporting}
                onOpenChange={setExporting}
                title="Start their export"
                change="A file of everything we hold about this person is made in the background and emailed to them when it's ready, as if they'd asked in Account. One a day."
                confirm="Start the export"
                onConfirm={async (reason) => {
                  await adminData(
                    adminApi.POST("/admin/v1/users/{id}/exports", {
                      params: { path: { id: ticket.userId! } },
                      body: { reason },
                    }),
                  );
                }}
              />
            )}
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}

/** For a privacy request: start their export, and where their deletion stands (never a way past its wait). */
function PrivacyRequest({ userId, onExport }: { userId: string; onExport: () => void }) {
  const deletion = useQuery({
    queryKey: [ADMIN, "deletion", userId],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/users/{id}/deletion", { params: { path: { id: userId } } })),
  });
  const request = deletion.data?.request;
  return (
    <Section
      title="Privacy request"
      action={
        <Button variant="secondary" onClick={onExport}>
          Start an export
        </Button>
      }
    >
      <Fields
        items={[
          [
            "Deletion",
            !deletion.data
              ? "…"
              : !request
                ? "Never asked for"
                : request.state === "scheduled" && request.deleteAfter
                  ? `Scheduled for ${formatDay(request.deleteAfter)}`
                  : request.state.replaceAll("_", " "),
          ],
          [
            "What stops it now",
            deletion.data
              ? deletion.data.blockers.length === 0
                ? "Nothing"
                : deletion.data.blockers.join(", ")
              : "…",
          ],
        ]}
      />
      <p className="text-small text-text-muted">
        The person deletes their account themselves, in Account, Your data. Its wait can&apos;t be skipped
        from here.
      </p>
    </Section>
  );
}
