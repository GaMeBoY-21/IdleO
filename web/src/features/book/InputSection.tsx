import { FileUp, RefreshCw, X } from "lucide-react";
import { useId, useRef, useState, type DragEvent } from "react";
import { useFormContext, useWatch } from "react-hook-form";
import type { Template } from "@/api/queries";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { ProgressBar, RadioGroup } from "@/components/ui/Inputs";
import { formatBytes } from "@/lib/bytes";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import type { BookValues } from "./form";
import type { ChosenFile } from "./useUpload";

const MIB = 1024 * 1024;

/** "about 40 s left", "about 3 min left". */
function timeLeft(seconds: number | null): string {
  if (seconds === null) {
    return "working out the time left";
  }
  if (seconds < 60) {
    return `about ${Math.max(1, seconds)} s left`;
  }
  return `about ${Math.ceil(seconds / 60)} min left`;
}

/**
 * The session's input, as the template takes it: a file, uploaded as soon as it's chosen; a link; or a choice of
 * the two. A file over the template's limit is refused before a byte is sent.
 */
export function InputSection({
  template,
  file,
  onChoose,
  onRemove,
  missing,
}: {
  template: Template;
  file: ChosenFile | null;
  onChoose: (file: File) => void;
  onRemove: () => void;
  missing: boolean;
}) {
  const form = useFormContext<BookValues>();
  const kind = useWatch({ control: form.control, name: "inputKind" });
  const { input } = template;
  const label = input.label ?? "Input";
  const optional = input.accepts === "optional";

  return (
    <section aria-labelledby="input-title" className="flex flex-col gap-4">
      <div>
        <h2 id="input-title" className="text-title font-semibold">
          {label}
          {optional && <span className="text-text-muted"> (optional)</span>}
        </h2>
        {input.help && <p className="text-small text-text-muted">{input.help}</p>}
      </div>
      {input.upload && input.url && (
        <RadioGroup
          legend="How you'll give it"
          options={[
            { value: "upload", label: "Upload a file" },
            { value: "url", label: "Give a link" },
          ]}
          inputProps={() => form.register("inputKind")}
        />
      )}
      {kind === "upload" && input.upload ? (
        <FilePicker
          maxBytes={input.maxSizeMb === null || input.maxSizeMb === undefined ? null : input.maxSizeMb * MIB}
          templateName={template.name}
          file={file}
          onChoose={onChoose}
          onRemove={onRemove}
          missing={missing}
        />
      ) : (
        <Field
          label="Link to the file"
          type="url"
          inputMode="url"
          autoComplete="off"
          placeholder="https://"
          hint="The session fetches it itself. It must start with https://"
          error={
            form.formState.errors.inputUrl?.message ??
            (missing ? "Enter a link, or upload a file." : undefined)
          }
          {...form.register("inputUrl")}
        />
      )}
    </section>
  );
}

function FilePicker({
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

  if (file) {
    const { progress } = file;
    const ready = progress.phase === "ready";
    const failed = progress.phase === "failed";
    return (
      <div className="flex flex-col gap-3 rounded-control border border-border p-4">
        <div className="flex items-start gap-3">
          <FileUp aria-hidden="true" className="mt-px size-5 shrink-0 text-text-muted" strokeWidth={1.75} />
          <div className="min-w-0 flex-1">
            <p className="text-body font-medium break-words">{file.name}</p>
            <p className="text-caption text-text-muted tabular" aria-live="polite">
              {ready
                ? `${formatBytes(file.size)} · uploaded`
                : failed
                  ? `${formatBytes(file.size)} · the upload stopped`
                  : progress.phase === "completing"
                    ? `${formatBytes(file.size)} · finishing`
                    : `${progress.percent}% of ${formatBytes(file.size)}${
                        progress.bytesPerSecond === null ? "" : ` · ${formatBytes(progress.bytesPerSecond)}/s`
                      } · ${timeLeft(progress.secondsLeft)}`}
            </p>
          </div>
          <Button
            variant="quiet"
            onClick={onRemove}
            aria-label={ready ? `Remove ${file.name}` : `Cancel the upload of ${file.name}`}
          >
            <X aria-hidden="true" className="size-4" strokeWidth={1.75} />
            {ready ? "Remove" : "Cancel"}
          </Button>
        </div>
        {!ready && !failed && <ProgressBar percent={progress.percent} label={`Uploading ${file.name}`} />}
        {failed && (
          <div className="flex flex-wrap items-center gap-3" role="alert">
            <p className="text-small text-danger">{problemMessage(progress.error)}</p>
            <Button variant="secondary" onClick={() => picker.current?.click()}>
              <RefreshCw aria-hidden="true" className="size-4" strokeWidth={1.75} />
              Choose it again
            </Button>
          </div>
        )}
        <input
          ref={picker}
          type="file"
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => take(event.target.files?.[0])}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {/* Dropping a file is a pointer shortcut; the "Choose a file" button inside is the way for everyone. */}
      {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions -- see above */}
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={drop}
        className={cn(
          "flex flex-col items-center gap-3 rounded-control border border-dashed px-4 py-8 text-center",
          over
            ? "border-accent bg-accent-soft"
            : missing || refused
              ? "border-danger"
              : "border-border-strong",
        )}
      >
        <FileUp aria-hidden="true" className="size-5 text-text-muted" strokeWidth={1.75} />
        <p className="text-small">Drop a file here, or</p>
        <Button variant="secondary" onClick={() => picker.current?.click()} aria-describedby={`${id}-limit`}>
          Choose a file
        </Button>
        <input
          ref={picker}
          type="file"
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          data-testid="input-file"
          onChange={(event) => take(event.target.files?.[0])}
        />
        <p id={`${id}-limit`} className="text-caption text-text-muted">
          {maxBytes === null ? "Any size." : `Up to ${formatBytes(maxBytes)}.`} It starts uploading straight
          away.
        </p>
      </div>
      <p className="min-h-4 text-caption text-danger" role={refused || missing ? "alert" : undefined}>
        {refused ?? (missing ? "Choose a file to upload." : "")}
      </p>
    </div>
  );
}
