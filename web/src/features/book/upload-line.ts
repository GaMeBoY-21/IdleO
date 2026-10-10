import { formatBytes } from "@/lib/bytes";
import type { InputUpload } from "./useUploads";

/** "about 40 s left", "about 3 min left", "about 2 h 10 min left". */
function timeLeft(seconds: number | null): string {
  if (seconds === null) {
    return "working out the time left";
  }
  if (seconds < 60) {
    return `about ${Math.max(1, seconds)} s left`;
  }
  const minutes = Math.ceil(seconds / 60);
  return minutes < 60
    ? `about ${minutes} min left`
    : `about ${Math.floor(minutes / 60)} h ${minutes % 60} min left`;
}

const files = (count: number) => `${count.toLocaleString("en-IN")} files`;

/**
 * How an upload stands, in a line: "4.6 GB · 4 files · in My files", "64% of 4.6 GB · 2.1 MB/s · about 3 min left",
 * and "carried on" once an interrupted upload has picked up where it stopped.
 */
export function uploadLine(upload: InputUpload): string {
  const { progress } = upload;
  const size = formatBytes(upload.size);
  const what = upload.folder ? `${size} · ${files(upload.fileCount)}` : size;
  switch (progress.phase) {
    case "ready":
      return `${what} · in My files`;
    case "failed":
      return `${what} · the upload stopped at ${progress.percent}%`;
    case "completing":
      return `${what} · finishing`;
    default:
      return [
        `${progress.percent}% of ${size}`,
        progress.resumedParts > 0 ? "carried on" : null,
        progress.bytesPerSecond === null ? null : `${formatBytes(progress.bytesPerSecond)}/s`,
        timeLeft(progress.secondsLeft),
      ]
        .filter(Boolean)
        .join(" · ");
  }
}
