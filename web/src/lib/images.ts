import type { Image } from "@/api/images";

/**
 * An image's size as registries count it, and as the server's own words do: "8.4 GB", "312 MB" (10^9 and 10^6 bytes,
 * ADR 0042). Files elsewhere are `formatBytes`'s.
 */
export function imageSize(bytes: number): string {
  if (bytes >= 1e9) {
    return `${(bytes / 1e9).toFixed(1)} GB`;
  }
  return `${Math.max(1, Math.ceil(bytes / 1e6))} MB`;
}

/** "3.1 of 8.4 GB", or "312 MB of 8.4 GB" when the two aren't in the same unit. */
export function sizeOf(done: number, total: number): string {
  const [doneText, doneUnit] = imageSize(done).split(" ");
  const totalText = imageSize(total);
  return totalText.endsWith(` ${doneUnit}`)
    ? `${doneText} of ${totalText}`
    : `${imageSize(done)} of ${totalText}`;
}

/** What an image's check is doing, in a line: "Downloading your image · 3.1 of 8.4 GB". */
export function checkLine(image: Pick<Image, "state" | "progress">): string {
  if (image.state === "queued") {
    return "Waiting for its check";
  }
  const progress = image.progress;
  switch (progress?.phase) {
    case "downloading":
      return progress.totalBytes > 0
        ? `Downloading your image · ${sizeOf(progress.doneBytes, progress.totalBytes)}`
        : "Downloading your image";
    case "reading":
      return "Reading every layer";
    case "scanning":
      return "Looking for known vulnerabilities and secrets";
    case "blocklist":
      return "Comparing it with our list of software that isn't allowed";
    default:
      return "Checking your image";
  }
}

/** The state as the renter reads it. */
export const IMAGE_STATES: Record<Image["state"], string> = {
  queued: "Waiting for its check",
  checking: "Being checked",
  ready: "Ready",
  blocked: "Blocked",
  failed: "Not checked",
};

/** A session's own image coming down to its host, before minute 0: "Downloading your image · 3.1 of 8.4 GB". */
export function downloadLine(download: { doneBytes: number; totalBytes: number }): string {
  return download.totalBytes > 0
    ? `Downloading your image · ${sizeOf(download.doneBytes, download.totalBytes)}`
    : "Downloading your image";
}

/** The short form of a digest, for a line: "sha256:1a2b3c4d…". */
export const shortDigest = (digest: string) => `${digest.slice(0, 15)}…`;
