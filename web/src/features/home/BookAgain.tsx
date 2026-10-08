import { History } from "lucide-react";
import { Link } from "wouter";
import { isLive, type Session } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { bookFrom, lastSetups } from "@/lib/sessions";
import { formatDuration } from "@/lib/time";

/** How many setups Book again offers. */
export const BOOK_AGAIN_COUNT = 3;

/**
 * "Book again" (docs/design/redesign/home-phone.html): the renter's last three distinct setups (a template, its
 * settings and its data) from sessions that have ended, each a row with when it ran, for how long and what it cost.
 * A tap opens Book filled in from it. A new renter has none, so the section isn't there.
 */
export function BookAgain({
  sessions,
  templateName,
}: {
  sessions: Session[];
  templateName: (id: string) => string;
}) {
  const setups = lastSetups(
    sessions.filter((session) => !isLive(session)),
    BOOK_AGAIN_COUNT,
  );
  if (setups.length === 0) {
    return null;
  }
  return (
    <section aria-labelledby="again-title" className="flex flex-col gap-1">
      <h2 id="again-title" className="mb-1 text-section font-bold">
        Book again
      </h2>
      <ul className="flex flex-col divide-y divide-divider">
        {setups.map((session) => (
          <li key={session.id}>
            <Link href={bookFrom(session)} className="flex items-center gap-3.5 py-3 hover:bg-fill">
              <span
                aria-hidden="true"
                className="flex size-10 shrink-0 items-center justify-center rounded-full bg-fill"
              >
                <History className="size-5" strokeWidth={1.75} />
              </span>
              <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="text-body font-semibold break-words">
                  {templateName(session.templateId)}
                </span>
                <span className="text-small text-text-muted tabular first-letter:uppercase">
                  <RelativeTime value={session.startedAt ?? session.createdAt} />
                  {session.startedAt && session.endedAt && (
                    <> · {formatDuration(session.startedAt, session.endedAt)}</>
                  )}
                  {" · "}
                  <Money paise={session.chargedPaise} />
                </span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
