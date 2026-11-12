import { useRef, useState } from "react";
import { useReport } from "@/api/trust";
import { Sheet, SheetClose } from "@/components/ui/Sheet";
import { TextArea } from "@/components/ui/Inputs";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Option } from "@/components/ui/Option";
import { problemMessage } from "@/lib/problems";
import { REPLY_PROMISE, REPORT_DETAILS_MAX, REPORT_REASONS } from "@/lib/reports";

/**
 * "Report a problem" (ADR 0028): the reason as option rows, what happened, and Send; afterwards, that we'll reply
 * within 2 days. A person reads every report. The Idempotency-Key belongs to the form as it stands, so sending again
 * after a lost answer returns the same report instead of making a second.
 */
export function ReportSheet({
  sessionId,
  open,
  onOpenChange,
}: {
  sessionId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const report = useReport();
  const [reason, setReason] = useState<string | null>(null);
  const [details, setDetails] = useState("");
  const attempt = useRef<{ body: string; key: string } | null>(null);
  const sent = report.isSuccess;

  const send = () => {
    if (reason === null) {
      return;
    }
    const body = JSON.stringify({ sessionId, reason, details: details.trim() });
    if (attempt.current?.body !== body) {
      attempt.current = { body, key: crypto.randomUUID() };
    }
    report.mutate({ sessionId, reason, details: details.trim(), idempotencyKey: attempt.current.key });
  };

  const close = (next: boolean) => {
    if (!next) {
      // A sent report is done with; an unsent one is kept, so closing by mistake loses nothing.
      if (sent) {
        report.reset();
        setReason(null);
        setDetails("");
        attempt.current = null;
      }
    }
    onOpenChange(next);
  };

  return (
    <Sheet
      open={open}
      onOpenChange={close}
      title={sent ? "Report sent" : "Report a problem"}
      description={
        <p className="text-small text-text-2">
          {sent ? (
            <>
              {REPLY_PROMISE} You&apos;ll see what we decided on this session&apos;s screen, and under
              Account, Reports.
            </>
          ) : (
            <>What went wrong with this session? A person reads every report. {REPLY_PROMISE}</>
          )}
        </p>
      }
    >
      {sent ? (
        <SheetClose asChild>
          <Button variant="primary" large>
            Done
          </Button>
        </SheetClose>
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            send();
          }}
        >
          <div role="group" aria-label="What went wrong" className="flex flex-col gap-1">
            {REPORT_REASONS.map((option) => (
              <Option
                key={option.value}
                title={option.title}
                detail={option.detail}
                selected={reason === option.value}
                onClick={() => setReason(option.value)}
              />
            ))}
          </div>
          <TextArea
            label="Tell us more (optional)"
            value={details}
            rows={4}
            maxLength={REPORT_DETAILS_MAX}
            onChange={(event) => setDetails(event.target.value)}
            hint={
              <>
                Don&apos;t include passwords or keys. <span className="tabular">{details.length}</span>/
                {REPORT_DETAILS_MAX}
              </>
            }
          />
          {report.isError && <Banner tone="danger">{problemMessage(report.error)}</Banner>}
          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <SheetClose asChild>
              <Button variant="ghost" large>
                Cancel
              </Button>
            </SheetClose>
            <Button
              type="submit"
              variant="primary"
              large
              busy={report.isPending}
              disabled={reason === null}
              title={reason === null ? "Choose what went wrong first" : undefined}
            >
              Send report
            </Button>
          </div>
        </form>
      )}
    </Sheet>
  );
}
