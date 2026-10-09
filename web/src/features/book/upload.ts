import type { components } from "@/api/schema.gen";

type UploadDetail = components["schemas"]["UploadDetail"];
type PartLink = components["schemas"]["UploadPartLink"];
type UploadedPart = components["schemas"]["UploadedPart"];

/**
 * One file uploaded straight to storage (ADR 0019, ADR 0022), as home internet allows:
 * - `POST /v1/uploads` opens it as an upload of one file; `POST /v1/uploads/{id}/part-urls` signs its parts' links,
 *   a hundred at a time; each part is a slice of the file (Blob.slice), never the whole file in memory, PUT with
 *   XMLHttpRequest, because fetch can't report upload progress; three at a time;
 * - each PUT's ETag is kept, then `POST /v1/uploads/{id}/complete` finishes it;
 * - a failed part is retried three times with backoff; an expired link (403) gets fresh links
 *   (`POST /v1/uploads/{id}/part-urls`) and the part is tried again;
 * - progress counts bytes storage has taken, not bytes handed to the browser, with speed and time left;
 * - cancelling aborts every request and deletes the upload.
 */
export interface UploadApi {
  create: (filename: string, sizeBytes: number, idempotencyKey: string) => Promise<UploadDetail>;
  partLinks: (id: string, path: string, partNumbers: number[]) => Promise<PartLink[]>;
  complete: (id: string, path: string, parts: UploadedPart[]) => Promise<unknown>;
  remove: (id: string) => Promise<unknown>;
}

/** The part of XMLHttpRequest an upload uses, so tests can stand in for it. */
export interface PartRequest {
  open(method: string, url: string): void;
  send(body: Blob): void;
  abort(): void;
  getResponseHeader(name: string): string | null;
  readonly status: number;
  upload: { onprogress: ((event: { loaded: number }) => void) | null };
  onload: (() => void) | null;
  onerror: (() => void) | null;
  onabort: (() => void) | null;
}

export type UploadPhase = "starting" | "uploading" | "completing" | "ready" | "failed" | "cancelled";

export interface UploadProgress {
  phase: UploadPhase;
  uploadId: string | null;
  sentBytes: number;
  totalBytes: number;
  /** Whole percent of bytes storage has taken; 100 only once the upload is ready. */
  percent: number;
  bytesPerSecond: number | null;
  secondsLeft: number | null;
  error: unknown;
}

export interface UploadDeps {
  api: UploadApi;
  request?: () => PartRequest;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
  idempotencyKey?: () => string;
}

const CONCURRENCY = 3;
/** The most part links one request asks for, as the API allows. */
const LINKS_PER_REQUEST = 100;
const RETRIES = 3;
/** Speed is measured over the last few seconds, so it follows the connection without jumping about. */
const SPEED_WINDOW_MS = 5_000;

class Cancelled extends Error {}
class PartFailed extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`part upload failed with ${status}`);
    this.status = status;
  }
}

export class Upload {
  private readonly file: Blob & { name: string };
  private readonly onProgress: (progress: UploadProgress) => void;
  private readonly api: UploadApi;
  private readonly request: () => PartRequest;
  private readonly now: () => number;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly key: string;
  private readonly inFlight = new Set<PartRequest>();
  /** Bytes storage has taken, per part; a part being retried starts again from 0. */
  private readonly sent = new Map<number, number>();
  private readonly samples: { at: number; bytes: number }[] = [];
  private cancelled = false;
  /** A part failed for good: the other parts stop too. */
  private failed = false;
  private progress: UploadProgress;

  constructor(
    file: Blob & { name: string },
    deps: UploadDeps,
    onProgress: (progress: UploadProgress) => void,
  ) {
    this.file = file;
    this.onProgress = onProgress;
    this.api = deps.api;
    this.request = deps.request ?? (() => new XMLHttpRequest() as unknown as PartRequest);
    this.now = deps.now ?? (() => performance.now());
    this.sleep = deps.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.key = (deps.idempotencyKey ?? (() => crypto.randomUUID()))();
    this.progress = {
      phase: "starting",
      uploadId: null,
      sentBytes: 0,
      totalBytes: file.size,
      percent: 0,
      bytesPerSecond: null,
      secondsLeft: null,
      error: null,
    };
  }

  /** Uploads the file; resolves with the upload's id once it's ready. */
  async start(): Promise<string> {
    try {
      const opened = await this.api.create(this.file.name, this.file.size, this.key);
      const id = opened.upload.id;
      const partSize = opened.upload.partSizeBytes;
      const partCount = opened.files[0]?.partCount ?? 0;
      this.update({ phase: "uploading", uploadId: id });
      this.throwIfCancelled();
      // Where speed is measured from: nothing sent, now.
      this.measure();
      const links = new Map<number, string>();
      for (let from = 1; from <= partCount; from += LINKS_PER_REQUEST) {
        const numbers = Array.from(
          { length: Math.min(LINKS_PER_REQUEST, partCount - from + 1) },
          (_, i) => from + i,
        );
        for (const link of await this.api.partLinks(id, this.file.name, numbers)) {
          links.set(link.number, link.url);
        }
        this.throwIfCancelled();
      }
      const queue = Array.from({ length: partCount }, (_, i) => i + 1);
      const parts: UploadedPart[] = [];
      const worker = async () => {
        for (let number = queue.shift(); number !== undefined; number = queue.shift()) {
          parts.push({ number, etag: await this.part(id, partSize, number, links) });
        }
      };
      await Promise.all(Array.from({ length: Math.min(CONCURRENCY, partCount) }, worker));
      this.throwIfCancelled();
      this.update({ phase: "completing" });
      await this.api.complete(
        id,
        this.file.name,
        parts.sort((a, b) => a.number - b.number),
      );
      this.throwIfCancelled();
      this.update({ phase: "ready", percent: 100, secondsLeft: 0 });
      return id;
    } catch (error) {
      if (this.cancelled) {
        throw new Cancelled("cancelled");
      }
      this.update({ phase: "failed", error });
      this.failed = true;
      this.abortAll();
      throw error;
    }
  }

  /** Stops every request and deletes what was uploaded. */
  async cancel(): Promise<void> {
    if (this.cancelled || this.progress.phase === "ready") {
      return;
    }
    this.cancelled = true;
    this.abortAll();
    this.update({ phase: "cancelled" });
    if (this.progress.uploadId !== null) {
      await this.api.remove(this.progress.uploadId);
    }
  }

  private async part(
    id: string,
    partSize: number,
    number: number,
    links: Map<number, string>,
  ): Promise<string> {
    const start = (number - 1) * partSize;
    const body = this.file.slice(start, Math.min(start + partSize, this.file.size));
    let failures = 0;
    let refreshes = 0;
    for (;;) {
      this.throwIfCancelled();
      try {
        return await this.put(links.get(number)!, body, number);
      } catch (error) {
        this.throwIfCancelled();
        this.sent.set(number, 0);
        this.measure();
        if (error instanceof PartFailed && error.status === 403 && refreshes < RETRIES) {
          // The part's link has expired: ask for a fresh one, and try again.
          refreshes++;
          for (const link of await this.api.partLinks(id, this.file.name, [number])) {
            links.set(link.number, link.url);
          }
          continue;
        }
        if (++failures > RETRIES) {
          throw error;
        }
        await this.sleep(1_000 * 2 ** (failures - 1));
      }
    }
  }

  private put(url: string, body: Blob, number: number): Promise<string> {
    return new Promise((resolve, reject) => {
      const request = this.request();
      this.inFlight.add(request);
      const done = () => this.inFlight.delete(request);
      request.upload.onprogress = (event) => {
        this.sent.set(number, Math.min(event.loaded, body.size));
        this.measure();
      };
      request.onload = () => {
        done();
        const etag = request.getResponseHeader("ETag");
        if (request.status >= 200 && request.status < 300 && etag) {
          this.sent.set(number, body.size);
          this.measure();
          resolve(etag);
        } else {
          reject(new PartFailed(request.status));
        }
      };
      request.onerror = () => {
        done();
        reject(new PartFailed(0));
      };
      request.onabort = () => {
        done();
        reject(new Cancelled("aborted"));
      };
      request.open("PUT", url);
      request.send(body);
    });
  }

  /** Progress from the bytes storage has taken, with the speed over the last few seconds and the time left. */
  private measure(): void {
    const sentBytes = [...this.sent.values()].reduce((sum, bytes) => sum + bytes, 0);
    const at = this.now();
    this.samples.push({ at, bytes: sentBytes });
    while (this.samples.length > 2 && at - this.samples[0]!.at > SPEED_WINDOW_MS) {
      this.samples.shift();
    }
    const first = this.samples[0]!;
    const elapsed = (at - first.at) / 1000;
    const bytesPerSecond =
      elapsed >= 0.5 && sentBytes > first.bytes ? (sentBytes - first.bytes) / elapsed : null;
    this.update({
      sentBytes,
      // Never 100 until storage has put the parts together.
      percent: Math.min(99, Math.floor((sentBytes / Math.max(1, this.file.size)) * 100)),
      bytesPerSecond,
      secondsLeft: bytesPerSecond === null ? null : Math.ceil((this.file.size - sentBytes) / bytesPerSecond),
    });
  }

  private update(change: Partial<UploadProgress>): void {
    if ((this.cancelled && change.phase !== "cancelled") || this.failed) {
      return;
    }
    this.progress = { ...this.progress, ...change };
    this.onProgress(this.progress);
  }

  private throwIfCancelled(): void {
    if (this.cancelled || this.failed) {
      throw new Cancelled("cancelled");
    }
  }

  private abortAll(): void {
    for (const request of this.inFlight) {
      request.abort();
    }
    this.inFlight.clear();
  }
}

export const isCancelled = (error: unknown) => error instanceof Cancelled;
