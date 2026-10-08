import { FileText, FileUp } from "lucide-react";
import { useId, useRef, useState, type DragEvent } from "react";
import { Button } from "@/components/ui/Button";
import { ProgressBar } from "@/components/ui/Inputs";
import { formatBytes } from "@/lib/bytes";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import type { ChosenFile } from "./useUpload";

/** "about 40 s left", "about 3 min left". */
function timeLeft(seconds: number | null): string {
  if (seconds === null) {
    return "working out the time left";
  }
  return seconds < 60 ? `about ${Math.max(1, seconds)} s left` : `about ${Math.ceil(seconds / 60)} min left`;
}

/** How the upload stands, in a line: "48.2 MB · uploaded", "64% of 48.2 MB · 2.1 MB/s · about 40 s left". */
export function uploadLine(file: ChosenFile): string {
  const { progress } = file;
  const size = formatBytes(file.size);
  switch (progress.phase) {
    case "ready":
      return `${size} · uploaded`;
    case "failed":
      return `${size} · the upload stopped`;
    case "completing":
      return `${size} · finishing`;
    default:
      return `${progress.percent}% of ${size}${
        progress.bytesPerSecond === null ? "" : ` · ${formatBytes(progress.bytesPerSecond)}/s`
      } · ${timeLeft(progress.secondsLeft)}`;
  }
}

/**
 * A file as the session's input (docs/design/redesign/book-laptop.html "Your data"): a grey row with its name and how
 * its upload stands, which starts as soon as it's chosen. A file over the template's limit is refused before a byte
 * is sent. Before one is chosen, the row is the place to choose or drop one.
 */
export function FilePicker({
  maxBytes,
  templateName,
  file,
  onChoose,
  onRemove,
  missing,
}: {
  maxBytes: number | null;
  templateName: string;
  file: ChosenFile | null;
  onChoose: (file: File) => void;
  onRemove: () => void;
  missing: boolean;
}) {
  const id = useId();
  const picker = useRef<HTMLInputElement>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [over, setOver] = useState(false);

  const take = (chosen: File | undefined) => {
    if (!chosen) {
      return;
    }
    if (maxBytes !== null && chosen.size > maxBytes) {
      setRefused(
        `${chosen.name} is ${formatBytes(chosen.size)}. ${templateName} takes files up to ${formatBytes(maxBytes)}.`,
      );
      return;
    }
    setRefused(null);
    onChoose(chosen);
  };
  const drop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    take(event.dataTransfer.files[0]);
  };
  const input = (
    <input
      ref={picker}
      type="file"
      className="sr-only"
      tabIndex={-1}
      aria-hidden="true"
      data-testid="input-file"
      onChange={(event) => {
        take(event.target.files?.[0]);
        event.target.value = "";
      }}
    />
  );
  const failed = file?.progress.phase === "failed";
  const ready = file?.progress.phase === "ready";

  return (
    <div className="flex flex-col gap-2">
      {/* Dropping a file is a pointer shortcut; the button inside is the way for everyone. */}
      {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions -- see above */}
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={drop}
        className={cn(
          "flex flex-col gap-3 rounded-control border-2 bg-fill px-4 py-3.5",
          over ? "border-text" : "border-transparent",
          !file && (missing || refused) && "ring-2 ring-danger",
        )}
      >
        <div className="flex items-center gap-3.5">
          {file ? (
            <FileText aria-hidden="true" className="size-5.5 shrink-0" strokeWidth={1.75} />
          ) : (
            <FileUp aria-hidden="true" className="size-5.5 shrink-0" strokeWidth={1.75} />
          )}
          <div className="flex min-w-0 flex-1 flex-col gap-0.5">
            <p className="text-body font-semibold break-words">{file ? file.name : "Choose a file"}</p>
            <p id={`${id}-line`} className="text-small text-text-muted tabular" aria-live="polite">
              {file
                ? uploadLine(file)
                : `${maxBytes === null ? "Any size" : `Up to ${formatBytes(maxBytes)}`}, or drop it here. It uploads straight away.`}
            </p>
          </div>
          {file && !ready && !failed ? (
            <Button variant="quiet" onClick={onRemove} aria-label={`Cancel the upload of ${file.name}`}>
              Cancel
            </Button>
          ) : (
            <span className="flex shrink-0">
              {file && (
                <Button
                  variant="quiet"
                  className="px-2"
                  onClick={onRemove}
                  aria-label={`Remove ${file.name}`}
                >
                  Remove
                </Button>
              )}
              <Button
                variant="quiet"
                className="px-2"
                onClick={() => picker.current?.click()}
                aria-describedby={`${id}-line`}
                aria-label={file ? `Change ${file.name}` : undefined}
              >
                {file ? "Change" : "Choose"}
              </Button>
            </span>
          )}
        </div>
        {file && !ready && !failed && (
          <ProgressBar percent={file.progress.percent} label={`Uploading ${file.name}`} />
        )}
        {input}
      </div>
      <p
        className="min-h-4.5 text-caption text-danger"
        role={refused || missing || failed ? "alert" : undefined}
      >
        {failed
          ? problemMessage(file.progress.error)
          : (refused ?? (missing ? "Choose a file to upload." : ""))}
      </p>
    </div>
  );
}
