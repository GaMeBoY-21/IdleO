import { describe, expect, it, vi } from "vitest";
import { isCancelled, Upload, type PartRequest, type UploadApi, type UploadProgress } from "./upload";

const MIB = 1024 * 1024;

/** A stand-in for XMLHttpRequest that the test answers by hand. */
class FakeRequest implements PartRequest {
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

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const waiting = () => FakeRequest.all.filter((r) => r.onload !== null && !r.settled && !r.aborted);

function setup(sizeBytes: number, partSize = 5 * MIB) {
  FakeRequest.all = [];
  const file = Object.assign(new Blob([new Uint8Array(sizeBytes)]), { name: "data.bin" });
  const partCount = Math.ceil(sizeBytes / partSize);
  const signed = new Set<number>();
  const api: UploadApi & Record<string, ReturnType<typeof vi.fn>> = {
    create: vi.fn(async (filename: string, size: number) => ({
      upload: {
        id: "u1",
        name: filename,
        sizeBytes: size,
        fileCount: 1,
        partSizeBytes: partSize,
        status: "pending" as const,
        createdAt: "",
        expiresAt: "",
      },
      files: [{ path: filename, sizeBytes: size, partCount, done: false }],
    })),
    // A part's first link, then a fresh one each time it's asked for again.
    partLinks: vi.fn(async (_id: string, _path: string, numbers: number[]) =>
      numbers.map((number) => {
        const fresh = signed.has(number);
        signed.add(number);
        return { number, url: `https://storage/part-${number}${fresh ? "?fresh" : ""}` };
      }),
    ),
    complete: vi.fn(async () => ({})),
    remove: vi.fn(async () => ({})),
  };
  const progress: UploadProgress[] = [];
  let clock = 0;
  const sleeps: number[] = [];
  const upload = new Upload(
    file,
    {
      api,
      request: () => new FakeRequest(),
      now: () => clock,
      sleep: async (ms) => {
        sleeps.push(ms);
      },
      idempotencyKey: () => "key-1",
    },
    (p) => progress.push(p),
  );
  return { upload, api, progress, sleeps, tick: (ms: number) => (clock += ms) };
}

describe("an upload", () => {
  it("sends each part as its own slice of the file, three at a time, then completes with the ETags", async () => {
    const { upload, api } = setup(12 * MIB, 5 * MIB);
    const done = upload.start();
    await flush();
    expect(api.create).toHaveBeenCalledWith("data.bin", 12 * MIB, "key-1");
    expect(waiting().map((r) => [r.url, r.body?.size])).toEqual([
      ["https://storage/part-1", 5 * MIB],
      ["https://storage/part-2", 5 * MIB],
      ["https://storage/part-3", 2 * MIB],
    ]);
    waiting().forEach((r, i) => r.ok(`"etag-${i + 1}"`));
    expect(await done).toBe("u1");
    expect(api.complete).toHaveBeenCalledWith("u1", "data.bin", [
      { number: 1, etag: '"etag-1"' },
      { number: 2, etag: '"etag-2"' },
      { number: 3, etag: '"etag-3"' },
    ]);
  });

  it("never has more than three parts in flight", async () => {
    const { upload } = setup(30 * MIB, 5 * MIB);
    const done = upload.start();
    for (let part = 1; part <= 6; part++) {
      await flush();
      expect(waiting().length).toBeLessThanOrEqual(3);
      waiting()[0]!.ok(`"e${part}"`);
    }
    await expect(done).resolves.toBe("u1");
    expect(FakeRequest.all).toHaveLength(6);
  });

  it("retries a failed part three times with backoff, then fails", async () => {
    const { upload, sleeps, progress } = setup(4 * MIB);
    const done = upload.start();
    for (let attempt = 0; attempt < 4; attempt++) {
      await flush();
      waiting()[0]!.fail(attempt % 2 === 0 ? 0 : 503);
    }
    await expect(done).rejects.toThrow("part upload failed with 503");
    expect(sleeps).toEqual([1_000, 2_000, 4_000]);
    expect(progress.at(-1)!.phase).toBe("failed");
  });

  it("gets a fresh link for a part whose link expired, and tries it again", async () => {
    const { upload, api } = setup(4 * MIB);
    const done = upload.start();
    await flush();
    waiting()[0]!.fail(403);
    await flush();
    await flush();
    expect(api.partLinks).toHaveBeenCalledWith("u1", "data.bin", [1]);
    expect(waiting()[0]!.url).toBe("https://storage/part-1?fresh");
    waiting()[0]!.ok('"e1"');
    await expect(done).resolves.toBe("u1");
  });

  it("counts progress from bytes storage has taken, with speed and time left, and 100% only when ready", async () => {
    const { upload, progress, tick } = setup(10 * MIB, 5 * MIB);
    const done = upload.start();
    await flush();
    const [first, second] = waiting();
    tick(1_000);
    first!.progress(2 * MIB);
    expect(progress.at(-1)).toMatchObject({
      sentBytes: 2 * MIB,
      percent: 20,
      bytesPerSecond: 2 * MIB,
      secondsLeft: 4,
    });
    tick(1_000);
    second!.progress(2 * MIB);
    expect(progress.at(-1)).toMatchObject({ sentBytes: 4 * MIB, percent: 40 });
    // A part that fails goes back to nothing sent.
    first!.fail(0);
    await flush();
    expect(progress.at(-1)!.sentBytes).toBe(2 * MIB);
    waiting().forEach((r) => r.ok('"e"'));
    expect(progress.filter((p) => p.phase === "uploading").every((p) => p.percent < 100)).toBe(true);
    await done;
    expect(progress.at(-1)).toMatchObject({ phase: "ready", percent: 100 });
  });

  it("cancels: aborts every request, deletes the upload, and says it's cancelled", async () => {
    const { upload, api, progress } = setup(12 * MIB, 5 * MIB);
    const done = upload.start();
    await flush();
    const inFlight = waiting();
    await upload.cancel();
    expect(inFlight.every((r) => r.aborted)).toBe(true);
    expect(api.remove).toHaveBeenCalledWith("u1");
    expect(api.complete).not.toHaveBeenCalled();
    await expect(done).rejects.toSatisfy(isCancelled);
    expect(progress.at(-1)!.phase).toBe("cancelled");
  });
});
