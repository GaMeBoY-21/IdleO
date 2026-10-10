import { ApiError } from "@/api/errors";
import type { components } from "@/api/schema.gen";
import { fingerprint, totalBytes, type Chosen } from "./chosen-files";
import { Cancelled, PartFailed, putPart, type PartRequest } from "./part-put";
import { browserResumeStore, type ResumeStore, type Unfinished } from "./upload-resume";

type UploadDetail = components["schemas"]["UploadDetail"];
type PartLink = components["schemas"]["UploadPartLink"];
type UploadedPart = components["schemas"]["UploadedPart"];

/**
 * A folder of files, or one file, uploaded straight to storage (ADR 0019, ADR 0022), as home internet allows:
 * - `POST /v1/uploads` opens it with each file's path and size; each file's parts are signed on demand
 *   (`POST /v1/uploads/{id}/part-urls`), a hundred at a time, so a 4,000-part model never asks for 4,000 links at once;
 * - each part is a slice of its file (Blob.slice), never a whole file in memory, PUT three at a time;
 * - each ETag is remembered in IndexedDB as it arrives (src/features/book/upload-resume.ts): choosing the same files
 *   again, after a reload or a dropped connection, opens the same upload with the same Idempotency-Key and sends
 *   only the parts storage hasn't taken;
 * - every file is then completed (`POST /v1/uploads/{id}/complete`), a hundred to a call;
 * - a failed part is retried three times with backoff; an expired link (403) gets a fresh one;
 * - progress counts bytes storage has taken, not bytes handed to the browser, with speed and time left;
 * - cancelling aborts every request, deletes the upload and forgets it.
 */
export interface UploadApi {
  create: (
    name: string,
    files: { path: string; sizeBytes: number }[],
    idempotencyKey: string,
  ) => Promise<UploadDetail>;
  partLinks: (id: string, path: string, partNumbers: number[]) => Promise<PartLink[]>;
  complete: (id: string, files: { path: string; parts: UploadedPart[] }[]) => Promise<unknown>;
  remove: (id: string) => Promise<unknown>;
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
  /** Parts storage already had from an earlier try, so weren't sent again; 0 for a fresh upload. */
  resumedParts: number;
  error: unknown;
}

export interface UploadDeps {
  api: UploadApi;
  resume?: ResumeStore;
  request?: () => PartRequest;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
  idempotencyKey?: () => string;
}

const CONCURRENCY = 3;
/** The most part links, and the most files to complete, one request takes, as the API allows. */
const PER_REQUEST = 100;
const RETRIES = 3;
/** Speed is measured over the last few seconds, so it follows the connection without jumping about. */
const SPEED_WINDOW_MS = 5_000;

const partKey = (path: string, number: number) => `${number}:${path}`;

export class Upload {
  private readonly chosen: Chosen;
  private readonly fp: string;
  private readonly total: number;
  private readonly onProgress: (progress: UploadProgress) => void;
  private readonly deps: Required<UploadDeps>;
  private readonly inFlight = new Set<PartRequest>();
  /** Bytes storage has taken of each part sent in this try; a part being retried starts again from 0. */
  private readonly sent = new Map<string, number>();
  /** Bytes storage already had when this try started: whole files, and parts from an earlier try. */
  private base = 0;
  private readonly links = new Map<string, Map<number, string>>();
  private readonly fetching = new Map<string, Promise<void>>();
  private readonly samples: { at: number; bytes: number }[] = [];
  private unfinished: Unfinished | null = null;
  private cancelled = false;
  /** A part failed for good: the other parts stop too. */
  private failed = false;
  private progress: UploadProgress;

  constructor(chosen: Chosen, deps: UploadDeps, onProgress: (progress: UploadProgress) => void) {
    this.chosen = chosen;
    this.fp = fingerprint(chosen);
    this.total = totalBytes(chosen);
    this.onProgress = onProgress;
    this.deps = {
      resume: browserResumeStore,
      request: () => new XMLHttpRequest() as unknown as PartRequest,
      now: () => performance.now(),
      sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
      idempotencyKey: () => crypto.randomUUID(),
      ...deps,
    };
    this.progress = {
      phase: "starting",
      uploadId: null,
      sentBytes: 0,
      totalBytes: this.total,
      percent: 0,
      bytesPerSecond: null,
      secondsLeft: null,
      resumedParts: 0,
      error: null,
    };
  }

  /** Uploads the files, or carries on with them; resolves with the upload's id once it's ready. */
  async start(): Promise<string> {
    try {
      const opened = await this.open();
      const id = opened.upload.id;
      const partSize = opened.upload.partSizeBytes;
      const record = this.unfinished!;
      this.update({ uploadId: id });
      const files = new Map(this.chosen.files.map(({ path, file }) => [path, file]));
      const todo: { path: string; number: number }[] = [];
      const left = opened.upload.status === "ready" ? [] : opened.files.filter((file) => !file.done);
      let resumed = 0;
      for (const file of opened.files) {
        if (!left.includes(file)) {
          this.base += file.sizeBytes;
          continue;
        }
        const taken = (record.parts[file.path] ??= {});
        for (let number = 1; number <= file.partCount; number++) {
          if (taken[number]) {
            this.base += Math.min(partSize, file.sizeBytes - (number - 1) * partSize);
            resumed++;
          } else {
            todo.push({ path: file.path, number });
          }
        }
      }
      this.throwIfCancelled();
      // Where speed is measured from: what storage has, now.
      this.measure();
      this.update({ phase: "uploading", resumedParts: resumed });
      const worker = async () => {
        for (let next = todo.shift(); next !== undefined; next = todo.shift()) {
          const { path, number } = next;
          const etag = await this.part(id, partSize, files.get(path)!, path, number, todo);
          record.parts[path]![number] = etag;
          void this.deps.resume.write(this.fp, record);
        }
      };
      await Promise.all(Array.from({ length: Math.min(CONCURRENCY, todo.length) }, worker));
      this.throwIfCancelled();
      this.update({ phase: "completing" });
      const finished = left.map((file) => ({
        path: file.path,
        parts: Object.entries(record.parts[file.path] ?? {})
          .map(([number, etag]) => ({ number: Number(number), etag }))
          .sort((a, b) => a.number - b.number),
      }));
      for (let from = 0; from < finished.length; from += PER_REQUEST) {
        await this.deps.api.complete(id, finished.slice(from, from + PER_REQUEST));
        this.throwIfCancelled();
      }
      await this.deps.resume.forget(this.fp);
      this.update({ phase: "ready", sentBytes: this.total, percent: 100, secondsLeft: 0 });
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

  /** Stops every request, deletes what was uploaded, and forgets it. */
  async cancel(): Promise<void> {
    if (this.cancelled || this.progress.phase === "ready") {
      return;
    }
    this.cancelled = true;
    this.abortAll();
    this.update({ phase: "cancelled" });
    await this.deps.resume.forget(this.fp);
    if (this.progress.uploadId !== null) {
      await this.deps.api.remove(this.progress.uploadId);
    }
  }

  /**
   * The upload these files went to before, if this browser remembers one that's still pending or ready, opened again
   * with its key; otherwise a new one.
   */
  private async open(): Promise<UploadDetail> {
    const { api, resume } = this.deps;
    const specs = this.chosen.files.map(({ path, file }) => ({ path, sizeBytes: file.size }));
    const saved = await resume.read(this.fp);
    if (saved) {
      try {
        const again = await api.create(this.chosen.name, specs, saved.key);
        if (
          again.upload.id === saved.uploadId &&
          (again.upload.status === "pending" || again.upload.status === "ready")
        ) {
          this.unfinished = saved;
          return again;
        }
      } catch (error) {
        if (!(error instanceof ApiError && error.status < 500)) {
          throw error;
        }
      }
      // Gone, failed, or refused: start over.
      await resume.forget(this.fp);
    }
    const key = this.deps.idempotencyKey();
    const opened = await api.create(this.chosen.name, specs, key);
    this.unfinished = { uploadId: opened.upload.id, key, parts: {} };
    await resume.write(this.fp, this.unfinished);
    return opened;
  }

  private async part(
    id: string,
    partSize: number,
    file: File,
    path: string,
    number: number,
    todo: readonly { path: string; number: number }[],
  ): Promise<string> {
    const start = (number - 1) * partSize;
    const body = file.slice(start, Math.min(start + partSize, file.size));
    const key = partKey(path, number);
    let failures = 0;
    let refreshes = 0;
    for (;;) {
      this.throwIfCancelled();
      try {
        const url = await this.link(id, path, number, todo);
        const request = this.deps.request();
        this.inFlight.add(request);
        try {
          return await putPart(request, url, body, (bytes) => {
            this.sent.set(key, bytes);
            this.measure();
          });
        } finally {
          this.inFlight.delete(request);
        }
      } catch (error) {
        this.throwIfCancelled();
        this.sent.set(key, 0);
        this.measure();
        if (error instanceof PartFailed && error.status === 403 && refreshes < RETRIES) {
          // The part's link has expired: forget it, so the next try asks for a fresh one.
          refreshes++;
          this.links.get(path)?.delete(number);
          continue;
        }
        if (++failures > RETRIES) {
          throw error;
        }
        await this.deps.sleep(1_000 * 2 ** (failures - 1));
      }
    }
  }

  /** The link for one part, asking for it and the file's next parts still to send, a hundred at most. */
  private async link(
    id: string,
    path: string,
    number: number,
    todo: readonly { path: string; number: number }[],
  ): Promise<string> {
    for (;;) {
      const url = this.links.get(path)?.get(number);
      if (url) {
        return url;
      }
      const asking = this.fetching.get(path);
      if (asking) {
        await asking;
        continue;
      }
      const wanted = [number, ...todo.filter((part) => part.path === path).map((part) => part.number)].slice(
        0,
        PER_REQUEST,
      );
      const fetch = this.deps.api.partLinks(id, path, wanted).then((links) => {
        const known = this.links.get(path) ?? new Map<number, string>();
        links.forEach((link) => known.set(link.number, link.url));
        this.links.set(path, known);
      });
      this.fetching.set(path, fetch);
      try {
        await fetch;
      } finally {
        this.fetching.delete(path);
      }
      this.throwIfCancelled();
      if (!this.links.get(path)?.has(number)) {
        throw new Error(`no link for part ${number} of ${path}`);
      }
    }
  }

  /** Progress from the bytes storage has taken, with the speed over the last few seconds and the time left. */
  private measure(): void {
    const sentBytes = this.base + [...this.sent.values()].reduce((sum, bytes) => sum + bytes, 0);
    const at = this.deps.now();
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
      percent: Math.min(99, Math.floor((sentBytes / Math.max(1, this.total)) * 100)),
      bytesPerSecond,
      secondsLeft: bytesPerSecond === null ? null : Math.ceil((this.total - sentBytes) / bytesPerSecond),
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
