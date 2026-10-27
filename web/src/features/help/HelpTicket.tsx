import { ChevronLeft } from "lucide-react";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { useContact, useReplyTicket, useTicket } from "@/api/support";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Inputs";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { problemMessage } from "@/lib/problems";
import { MAX_MESSAGE, STATUS_WORDS, ticketNumber } from "@/lib/support";
import { formatDateTime, formatDay } from "@/lib/time";
import { useTitle } from "@/lib/title";

/**
 * One of the person's messages to us and our answers (ADR 0037), with a reply box: "Reply here, not by email", since
 * our address takes no replies. Someone else's is not found.
 */
export function HelpTicket() {
  const id = new URLSearchParams(useSearch()).get("id") ?? "";
  const thread = useTicket(id);
  const contact = useContact();
  const reply = useReplyTicket(id);
  const [message, setMessage] = useState("");
  const ticket = thread.data?.ticket;
  useTitle(ticket ? `Message ${ticketNumber(ticket.number)}` : "Message");
  const since = staleSince(thread);
  const topic = ticket
    ? (contact.data?.topics.find((candidate) => candidate.code === ticket.topic)?.label ?? ticket.topic)
    : null;

  return (
    <AccountFrame title={ticket ? `Message ${ticketNumber(ticket.number)}` : "Message"}>
      <Link
        href="/account/help/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-2 underline"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Help
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <Tile as="section" aria-labelledby="thread-title" className="flex flex-col gap-3 md:p-6 xl:p-7">
        <SectionTitle id="thread-title">{topic ?? " "}</SectionTitle>
        {thread.data && ticket ? (
          <>
            <p className="text-small text-text-2">
              {STATUS_WORDS[ticket.status] ?? ticket.status} · sent {formatDay(ticket.createdAt)}
              {ticket.dueAt ? ` · we answer by ${formatDay(ticket.dueAt)}` : ""}
            </p>
            <ol className="flex flex-col gap-3">
              {thread.data.messages.map((entry) => (
                <li
                  key={`${entry.createdAt}${entry.author}`}
                  className={`flex flex-col gap-1 rounded-control p-4 ${entry.author === "admin" ? "bg-canvas" : "bg-panel tiled:bg-panel-2"}`}
                >
                  <p className="text-caption text-text-2">
                    {entry.author === "admin" ? "We wrote" : "You wrote"}, {formatDateTime(entry.createdAt)}
                  </p>
                  <p className="whitespace-pre-wrap break-words text-body">{entry.body}</p>
                </li>
              ))}
            </ol>
            <form
              noValidate
              className="flex flex-col gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                if (message.trim() !== "") {
                  reply.mutate(message.trim(), { onSuccess: () => setMessage("") });
                }
              }}
            >
              <TextArea
                label="Reply here, not by email"
                maxLength={MAX_MESSAGE}
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                error={reply.isError ? problemMessage(reply.error) : undefined}
                hint="Our email address takes no replies. We email you when we answer."
              />
              <Button type="submit" busy={reply.isPending} className="self-start">
                Send reply
              </Button>
            </form>
          </>
        ) : thread.isError ? (
          <ErrorState
            error={thread.error}
            what="this message"
            onRetry={() => void thread.refetch()}
            retrying={thread.isFetching}
          />
        ) : (
          <div className="flex flex-col gap-3" role="status" aria-label="Loading this message">
            <SkeletonLine size="small" width="w-1/2" />
            <SkeletonLine size="body" width="w-full" />
            <SkeletonLine size="body" width="w-5/6" />
          </div>
        )}
      </Tile>
    </AccountFrame>
  );
}
