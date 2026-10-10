import { FileText, FileUp, Folder, FolderUp } from "lucide-react";
import { useId, useRef, useState, type DragEvent } from "react";
import type { components } from "@/api/schema.gen";
import { Button } from "@/components/ui/Button";
import { ProgressBar } from "@/components/ui/Inputs";
import { formatBytes } from "@/lib/bytes";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import {
  fromDrop,
  fromFolderPicker,
  MAX_FILES,
  oneFile,
  totalBytes,
  type Chosen,
  type DroppedEntry,
} from "./chosen-files";
import { uploadLine } from "./upload-line";
import type { InputUpload } from "./useUploads";

type TemplateInput = components["schemas"]["TemplateInput"];

/** Why these files can't be this input, before a byte is sent; null when they can. */
export function refusal(input: TemplateInput, chosen: Chosen): string | null {
  if (input.kind === "file" && chosen.folder) {
    return `${input.label} takes one file, not a folder.`;
  }
  if (chosen.files.length > MAX_FILES) {
    return `${chosen.name} has ${chosen.files.length.toLocaleString("en-IN")} files. An upload holds up to ${MAX_FILES.toLocaleString("en-IN")}: zip the folder and upload the zip.`;
  }
  const size = totalBytes(chosen);
  if (input.maxSizeBytes != null && size > input.maxSizeBytes) {
    return `${chosen.name} is ${formatBytes(size)}. ${input.label} takes up to ${formatBytes(input.maxSizeBytes)}.`;
  }
  return null;
}

/**
 * An input's upload (docs/design/redesign/book-finetune-laptop.html): a grey row with its name and how the upload
 * stands. It starts as soon as the files are chosen, with "Choose folder" for a folder input beside "Choose file",
 * or by dropping either on the row. Files the input can't take are refused before anything is sent; an upload that
 * stopped can be tried again, carrying on from where it stopped.
 */
export function FilePicker({
  input,
  upload,
  onChoose,
  onRemove,
  onRetry,
  missing,
}: {
  input: TemplateInput;
  upload: InputUpload | null;
  onChoose: (chosen: Chosen) => void;
  onRemove: () => void;
  onRetry: () => void;
  missing: boolean;
}) {
  const id = useId();
  const filePicker = useRef<HTMLInputElement>(null);
  const folderPicker = useRef<HTMLInputElement>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [over, setOver] = useState(false);
  const folder = input.kind === "folder";

  const take = (chosen: Chosen | null) => {
    if (!chosen) {
      return;
    }
    const why = refusal(input, chosen);
    setRefused(why);
    if (why === null) {
      onChoose(chosen);
    }
  };
  const drop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    const item = event.dataTransfer.items[0];
    const entry = (item?.webkitGetAsEntry() ?? null) as DroppedEntry | null;
    void fromDrop(entry, event.dataTransfer.files[0] ?? null).then(take);
  };
  const failed = upload?.progress.phase === "failed";
  const ready = upload?.progress.phase === "ready";
  const going = upload !== null && !ready && !failed;
  const Icon = upload ? (upload.folder ? Folder : FileText) : folder ? FolderUp : FileUp;
  const label = upload?.name ?? input.label;
  const open = (which: "file" | "folder") =>
    (which === "file" ? filePicker.current : folderPicker.current)?.click();

  return (
    <div className="flex flex-col gap-2">
      {/* Dropping is a pointer shortcut; the buttons inside are the way for everyone. */}
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
          !upload && (missing || refused) && "ring-2 ring-danger",
        )}
      >
        <div className="flex flex-wrap items-center gap-x-3.5 gap-y-2">
          <Icon aria-hidden="true" className="size-5.5 shrink-0" strokeWidth={1.75} />
          <div className="flex min-w-0 flex-1 basis-menu flex-col gap-0.5">
            <p className="text-body font-semibold break-words">
              {upload
                ? `${upload.name}${upload.folder ? "/" : ""}`
                : folder
                  ? "Choose a folder, or one file"
                  : "Choose a file"}
            </p>
            <p id={`${id}-line`} className="text-small text-text-muted tabular" aria-live="polite">
              {upload
                ? uploadLine(upload)
                : `${input.maxSizeBytes == null ? "Any size" : `Up to ${formatBytes(input.maxSizeBytes)}`}, or drop it here. It uploads straight away.`}
            </p>
          </div>
          <span className="flex shrink-0">
            {going ? (
              <Button variant="quiet" onClick={onRemove} aria-label={`Cancel the upload of ${label}`}>
                Cancel
              </Button>
            ) : (
              <>
                {failed && (
                  <Button variant="quiet" className="px-2" onClick={onRetry}>
                    Try again
                  </Button>
                )}
                {upload && (
                  <Button variant="quiet" className="px-2" onClick={onRemove} aria-label={`Remove ${label}`}>
                    Remove
                  </Button>
                )}
                {(upload || folder) && (
                  <Button
                    variant="quiet"
                    className="px-2"
                    onClick={() => open(folder ? "folder" : "file")}
                    aria-describedby={`${id}-line`}
                    aria-label={upload ? `Change ${label}` : undefined}
                  >
                    {upload ? "Change" : "Choose folder"}
                  </Button>
                )}
                {!upload && (
                  <Button
                    variant="quiet"
                    className="px-2"
                    onClick={() => open("file")}
                    aria-describedby={`${id}-line`}
                  >
                    {folder ? "Choose file" : "Choose"}
                  </Button>
                )}
              </>
            )}
          </span>
        </div>
        {going && <ProgressBar percent={upload.progress.percent} label={`Uploading ${label}`} />}
        <input
          ref={filePicker}
          type="file"
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          data-testid={`${input.name}-file`}
          onChange={(event) => {
            const file = event.target.files?.[0];
            take(file ? oneFile(file) : null);
            event.target.value = "";
          }}
        />
        {folder && (
          <input
            ref={folderPicker}
            type="file"
            // A folder, with each file's path inside it (webkitRelativePath).
            {...{ webkitdirectory: "" }}
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            data-testid={`${input.name}-folder`}
            onChange={(event) => {
              take(fromFolderPicker(event.target.files ?? []));
              event.target.value = "";
            }}
          />
        )}
      </div>
      <p
        className="min-h-4.5 text-caption text-danger"
        role={refused || missing || failed ? "alert" : undefined}
      >
        {failed
          ? `${problemMessage(upload.progress.error)} Trying again carries on from where it stopped.`
          : (refused ?? (missing ? `Choose ${folder ? "your folder" : "a file"} to upload.` : ""))}
      </p>
    </div>
  );
}
