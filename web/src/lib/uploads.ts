import type { UploadInfo } from "@/api/queries";
import { formatBytes } from "./bytes";
import { formatRelative } from "./time";

/** "my-llm-7b/" for a folder of files, "examples.jsonl" for one: an upload's name as My files shows it. */
export const uploadName = (upload: Pick<UploadInfo, "name" | "fileCount">) =>
  upload.fileCount > 1 ? `${upload.name}/` : upload.name;

/**
 * An upload in a line (ADR 0022): "4.6 GB · 4 files · last used 3 days ago". One still going says so; one never used
 * says when it was uploaded.
 */
export function uploadFacts(upload: UploadInfo, now: number): string {
  const size = formatBytes(upload.sizeBytes);
  const files = upload.fileCount > 1 ? `${upload.fileCount.toLocaleString("en-IN")} files` : null;
  const when =
    upload.status === "pending"
      ? "still uploading"
      : upload.lastUsedAt
        ? `last used ${formatRelative(upload.lastUsedAt, now)}`
        : `uploaded ${formatRelative(upload.completedAt ?? upload.createdAt, now)}`;
  return [size, files, when].filter(Boolean).join(" · ");
}
