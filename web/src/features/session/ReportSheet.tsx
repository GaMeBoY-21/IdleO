import { Dialog } from "radix-ui";
import { useRef, useState } from "react";
import { useReport } from "@/api/trust";
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
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Portal>
        <Dialog.Overlay className="appear fixed inset-0 z-30 bg-primary opacity-40" />
        <Dialog.Content className="appear fixed inset-x-4 top-4 bottom-4 z-40 mx-auto flex max-w-narrow flex-col gap-4 overflow-y-auto rounded-tile bg-ground p-6 shadow-float md:inset-y-auto md:top-12 md:max-h-5/6">
          {sent ? (
            <>
              <Dialog.Title className="text-section">Report sent</Dialog.Title>
              <Dialog.Description className="text-small text-text-muted">
                {REPLY_PROMISE} You&apos;ll see what we decided on this session&apos;s screen, and under
                Account, Reports.
              </Dialog.Description>
              <Dialog.Close asChild>
                <Button variant="primary" large>
                  Done
                </Button>
              </Dialog.Close>
            </>
          ) : (
            <>
              <Dialog.Title className="text-section">Report a problem</Dialog.Title>
              <Dialog.Description className="text-small text-text-muted">
                What went wrong with this session? A person reads every report. {REPLY_PROMISE}
              </Dialog.Description>
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
                <div className="flex flex-col gap-2">
                  <label htmlFor="report-details" className="text-small font-medium">
                    Tell us more (optional)
                  </label>
                  <textarea
                    id="report-details"
                    value={details}
                    rows={4}
                    maxLength={REPORT_DETAILS_MAX}
                    onChange={(event) => setDetails(event.target.value)}
                    aria-describedby="report-details-help"
                    className="rounded-control bg-fill px-4 py-3 text-body text-text"
                  />
                  <p id="report-details-help" className="text-caption text-text-muted">
                    Don&apos;t include passwords or keys. <span className="tabular">{details.length}</span>/
                    {REPORT_DETAILS_MAX}
                  </p>
                </div>
                {report.isError && <Banner tone="danger">{problemMessage(report.error)}</Banner>}
                <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
                  <Dialog.Close asChild>
                    <Button variant="secondary" large>
                      Cancel
                    </Button>
                  </Dialog.Close>
                  <Button
                    type="submit"
                    variant="primary"
                    large
                    busy={report.isPending}
                    disabled={reason === null}
                  >
                    Send report
                  </Button>
                </div>
              </form>
            </>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
