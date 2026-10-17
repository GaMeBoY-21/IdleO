import { Check, Copy, RefreshCw } from "lucide-react";
import { useState } from "react";
import { config } from "@/config";
import { Button, buttonClasses } from "@/components/ui/Button";
import { useNow } from "@/lib/clock";
import { formatCountdown } from "@/lib/hosting";
import type { EnrolmentCode as Code } from "@/api/hosting";

/**
 * The step-two card (docs/design/hosting/add-machine-laptop.html): the one-time code, shown once and never stored, with a
 * live countdown, Copy code and New code; and the way to install the host app, which is the runbook until a
 * Windows installer exists. The code is held in memory only: a reload loses it, and New code issues another.
 */
export function EnrolmentCodeCard({
  code,
  issuing,
  onNewCode,
}: {
  code: Code | null;
  issuing: boolean;
  onNewCode: () => void;
}) {
  const now = useNow("second");
  const [copied, setCopied] = useState(false);
  const left = code ? formatCountdown(code.expiresAt, now) : null;
  const usable = code !== null && left !== null;

  return (
    <div className="flex flex-col gap-4">
      <p className="text-small text-text-muted">Install the host app and enter this code.</p>
      <div className="flex flex-col gap-1 rounded-control bg-ground p-5">
        <p className="text-section tabular break-all md:text-title">{usable ? code.code : "No code yet"}</p>
        <p className="min-h-5 text-small text-text-muted tabular" role="timer">
          {usable
            ? `Works once · expires in ${left}`
            : code
              ? "This code has expired. Get a new one."
              : "Get a code to continue."}
        </p>
      </div>
      <div className="flex flex-col gap-2 md:flex-row">
        <a
          href={config.hostRunbookUrl}
          target="_blank"
          rel="noreferrer"
          className={buttonClasses("primary", true)}
        >
          Install the host app
        </a>
        <Button
          variant="secondary"
          block
          disabled={!usable}
          onClick={() => {
            if (code) {
              void navigator.clipboard.writeText(code.code).then(() => setCopied(true));
            }
          }}
        >
          {copied ? (
            <Check aria-hidden="true" className="size-4" strokeWidth={2} />
          ) : (
            <Copy aria-hidden="true" className="size-4" strokeWidth={1.75} />
          )}
          {copied ? "Copied" : "Copy code"}
        </Button>
        <Button
          variant="secondary"
          block
          busy={issuing}
          onClick={() => {
            setCopied(false);
            onNewCode();
          }}
        >
          {!issuing && <RefreshCw aria-hidden="true" className="size-4" strokeWidth={1.75} />}
          New code
        </Button>
      </div>
      <p className="text-small text-text-muted">
        The Windows app is coming; for now run the agent from the runbook. It needs Windows 10 or 11 and an
        NVIDIA RTX GPU. On Linux, run <span className="font-semibold">idleo-agent enrol</span> with the code.
      </p>
    </div>
  );
}
