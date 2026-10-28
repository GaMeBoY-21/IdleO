import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "wouter";
import { useHosts } from "@/api/hosting";
import { useMe, useSessions, useTemplateName } from "@/api/queries";
import { useContact, useOpenTicket, useTickets, type SupportTicket } from "@/api/support";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Select, TextArea } from "@/components/ui/Inputs";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { useAfterPaint } from "@/lib/after-first-screen";
import { problemMessage } from "@/lib/problems";
import { MAX_MESSAGE, STATUS_WORDS, ticketNumber } from "@/lib/support";
import { formatDay } from "@/lib/time";
import { useTitle } from "@/lib/title";

const ROW = "flex min-h-18 items-center gap-3 py-3";

/**
 * Account → Help (ADR 0037): a message to us, about one of the person's sessions or machines if they like, and every
 * message they've sent with where it stands. Answers come by email and are read and answered here.
 */
export function Help() {
  useTitle("Help");
  const painted = useAfterPaint();
  const tickets = useTickets(painted);
  const since = staleSince(tickets);

  return (
    <AccountFrame title="Help" className="xl:grid xl:grid-cols-5 xl:items-start">
      <Link
        href="/account/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-2 underline md:hidden"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Account
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <Tile
        as="section"
        aria-labelledby="write-title"
        className="flex flex-col gap-3 md:p-6 xl:col-span-3 xl:p-7"
      >
        <SectionTitle id="write-title">Send us a message</SectionTitle>
        <p className="max-w-prose text-small text-text-2">
          A person reads every message. We email you when we answer, and you can reply here.
        </p>
        <TicketForm painted={painted} />
      </Tile>
      <Tile
        as="section"
        aria-labelledby="sent-title"
        className="flex flex-col gap-1 md:p-6 xl:col-span-2 xl:p-7"
      >
        <SectionTitle id="sent-title">Your messages</SectionTitle>
        {tickets.data ? (
          tickets.data.tickets.length === 0 ? (
            <EmptyState title="No messages yet">
              What you send us is listed here, with our answers.
            </EmptyState>
          ) : (
            <ul className="flex flex-col divide-y divide-line">
              {tickets.data.tickets.map((ticket) => (
                <TicketRow key={ticket.id} ticket={ticket} />
              ))}
            </ul>
          )
        ) : tickets.isError ? (
          <ErrorState
            error={tickets.error}
            what="your messages"
            onRetry={() => void tickets.refetch()}
            retrying={tickets.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-line"
            role="status"
            aria-label="Loading your messages"
          >
            {[0, 1].map((key) => (
              <div key={key} className={ROW}>
                <div className="flex flex-1 flex-col gap-0.5">
                  <SkeletonLine size="option" width="w-1/2" />
                  <SkeletonLine size="small" width="w-2/3" />
                </div>
              </div>
            ))}
          </div>
        )}
      </Tile>
    </AccountFrame>
  );
}

function TicketRow({ ticket }: { ticket: SupportTicket }) {
  const contact = useContact();
  const topic =
    contact.data?.topics.find((candidate) => candidate.code === ticket.topic)?.label ?? ticket.topic;
  return (
    <li>
      <Link href={`/account/help/ticket/?id=${ticket.id}`} className={`${ROW} hover:bg-panel-2`}>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className="truncate text-option font-semibold">
            {ticketNumber(ticket.number)} · {topic}
          </p>
          <p className="truncate text-small text-text-2">
            {STATUS_WORDS[ticket.status] ?? ticket.status} · sent {formatDay(ticket.createdAt)}
          </p>
        </div>
        <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-text-2" strokeWidth={1.75} />
      </Link>
    </li>
  );
}

/** The form: its fields are drawn whole from the start, so what arrives (the topics, the address) moves nothing. */
function TicketForm({ painted }: { painted: boolean }) {
  // When the form appeared: a message sent within seconds of it isn't a person's (the server's check, Tickets).
  const opened = useRef(0);
  useEffect(() => {
    opened.current = Date.now();
  }, []);
  const me = useMe();
  const contact = useContact(painted);
  const sessions = useSessions(painted);
  const hosts = useHosts(painted);
  const templateName = useTemplateName();
  const send = useOpenTicket();
  const [topic, setTopic] = useState("");
  const [about, setAbout] = useState("");
  const [message, setMessage] = useState("");
  const [replyTo, setReplyTo] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const address = replyTo ?? me.data?.email ?? "";

  const submit = () => {
    if (topic === "" || message.trim() === "" || address.trim() === "") {
      setMissing(true);
      return;
    }
    setMissing(false);
    const [kind, id] = about.split(":");
    send.mutate(
      {
        topic,
        message: message.trim(),
        replyTo: address.trim(),
        sessionId: kind === "session" ? id : null,
        hostId: kind === "host" ? id : null,
        website: "",
        elapsedMs: Date.now() - opened.current,
      },
      {
        onSuccess: () => {
          setMessage("");
          setAbout("");
        },
      },
    );
  };

  const sessionList = sessions.data?.sessions ?? [];
  const hostList = hosts.data?.hosts ?? [];
  return (
    <form
      noValidate
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <Select
        label="What it's about"
        value={topic}
        disabled={!contact.data}
        onChange={(event) => setTopic(event.target.value)}
        error={missing && topic === "" ? "Choose what it's about." : undefined}
      >
        <option value="">Choose…</option>
        {contact.data?.topics.map((option) => (
          <option key={option.code} value={option.code}>
            {option.label}
          </option>
        ))}
      </Select>
      <Select
        label="Which session or machine (if any)"
        value={about}
        onChange={(event) => setAbout(event.target.value)}
      >
        <option value="">None in particular</option>
        {sessionList.length > 0 && (
          <optgroup label="Your sessions">
            {sessionList.map((session) => (
              <option key={session.id} value={`session:${session.id}`}>
                {templateName(session.templateId)} · {formatDay(session.createdAt)}
              </option>
            ))}
          </optgroup>
        )}
        {hostList.length > 0 && (
          <optgroup label="Your machines">
            {hostList.map((host) => (
              <option key={host.id} value={`host:${host.id}`}>
                {host.name}
              </option>
            ))}
          </optgroup>
        )}
      </Select>
      <TextArea
        label="Your message"
        rows={6}
        maxLength={MAX_MESSAGE}
        value={message}
        onChange={(event) => setMessage(event.target.value)}
        error={missing && message.trim() === "" ? "Write your message." : undefined}
        hint={`${message.length.toLocaleString("en-IN")} of ${MAX_MESSAGE.toLocaleString("en-IN")} characters`}
      />
      <Field
        label="Where we answer"
        type="email"
        autoComplete="email"
        value={address}
        onChange={(event) => setReplyTo(event.target.value)}
        error={missing && address.trim() === "" ? "Give an email address." : undefined}
      />
      <p role="status" className="min-h-4.5 text-caption">
        {send.isSuccess
          ? `Sent as ${ticketNumber(send.data.number)}. We've emailed you to say we have it.`
          : send.isError
            ? problemMessage(send.error)
            : ""}
      </p>
      <Button type="submit" busy={send.isPending} className="self-start">
        Send
      </Button>
    </form>
  );
}
