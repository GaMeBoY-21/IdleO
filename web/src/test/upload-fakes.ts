import { vi } from "vitest";
import type { Chosen } from "@/features/book/chosen-files";
import type { PartRequest } from "@/features/book/part-put";
import type { UploadApi } from "@/features/book/upload";

export const MIB = 1024 * 1024;

/** A stand-in for XMLHttpRequest that a test answers by hand. */
export class FakeRequest implements PartRequest {
  static all: FakeRequest[] = [];
  url = "";
  body: Blob | null = null;
  status = 0;
  etag: string | null = null;
  aborted = false;
  settled = false;
  upload: PartRequest["upload"] = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  constructor() {
    FakeRequest.all.push(this);
  }
  open(_method: string, url: string) {
    this.url = url;
  }
  send(body: Blob) {
    this.body = body;
  }
  abort() {
    this.aborted = true;
    this.onabort?.();
  }
  getResponseHeader(name: string) {
    return name === "ETag" ? this.etag : null;
  }
  progress(loaded: number) {
    this.upload.onprogress?.({ loaded });
  }
  ok(etag: string) {
    this.settled = true;
    this.status = 200;
    this.etag = etag;
    this.onload?.();
  }
  fail(status: number) {
    this.settled = true;
    this.status = status;
    if (status === 0) {
      this.onerror?.();
    } else {
      this.onload?.();
    }
  }
}

export const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
/** The part PUTs sent and not yet answered. */
export const waiting = () => FakeRequest.all.filter((r) => r.onload !== null && !r.settled && !r.aborted);

/** Files of these sizes, as a folder `name` (or one file, when there's one and `folder` is false). */
export function chosenFiles(name: string, sizes: Record<string, number>, folder = true): Chosen {
  return {
    name,
    folder,
    files: Object.entries(sizes).map(([path, size]) => ({
      path,
      file: new File([new Uint8Array(size)], path.slice(path.lastIndexOf("/") + 1), { lastModified: 1 }),
    })),
  };
}

type Status = "pending" | "ready" | "failed" | "expired" | "deleted";

/**
 * The uploads API as the control plane answers it: an upload per Idempotency-Key (the same key returns the same
 * upload, as it stands), each file done once completed, a fresh link each time a part is asked for again.
 */
export function fakeUploadApi(partSize = 5 * MIB) {
  const uploads = new Map<
    string,
    { id: string; status: Status; files: Map<string, number>; done: Set<string> }
  >();
  const signed = new Set<string>();
  const detail = (upload: NonNullable<ReturnType<typeof uploads.get>>, name: string) => ({
    upload: {
      id: upload.id,
      name,
      sizeBytes: [...upload.files.values()].reduce((sum, size) => sum + size, 0),
      fileCount: upload.files.size,
      partSizeBytes: partSize,
      status: upload.status,
      createdAt: "",
      expiresAt: "",
    },
    files: [...upload.files].map(([path, sizeBytes]) => ({
      path,
      sizeBytes,
      partCount: Math.ceil(sizeBytes / partSize),
      done: upload.done.has(path),
    })),
  });
  const byId = (id: string) => [...uploads.values()].find((upload) => upload.id === id)!;
  const api = {
    create: vi.fn(async (name: string, files: { path: string; sizeBytes: number }[], key: string) => {
      let upload = uploads.get(key);
      if (!upload) {
        upload = {
          id: `u${uploads.size + 1}`,
          status: "pending",
          files: new Map(files.map((file) => [file.path, file.sizeBytes])),
          done: new Set(),
        };
        uploads.set(key, upload);
      }
      return detail(upload, name);
    }),
    partLinks: vi.fn(async (id: string, path: string, numbers: number[]) =>
      numbers.map((number) => {
        const fresh = signed.has(`${id}/${path}/${number}`);
        signed.add(`${id}/${path}/${number}`);
        return { number, url: `https://storage/${id}/${path}/part-${number}${fresh ? "?fresh" : ""}` };
      }),
    ),
    complete: vi.fn(async (id: string, files: { path: string; parts: unknown[] }[]) => {
      const upload = byId(id);
      files.forEach((file) => upload.done.add(file.path));
      if (upload.done.size === upload.files.size) {
        upload.status = "ready";
      }
      return {};
    }),
    remove: vi.fn(async (id: string) => {
      byId(id).status = "deleted";
      return {};
    }),
  } satisfies UploadApi;
  return { api, setStatus: (id: string, status: Status) => (byId(id).status = status) };
}
