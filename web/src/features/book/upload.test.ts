import { describe, expect, it } from "vitest";
import { chosenFiles, fakeUploadApi, FakeRequest, flush, MIB, waiting } from "@/test/upload-fakes";
import type { Chosen } from "./chosen-files";
import { isCancelled, Upload, type UploadProgress } from "./upload";
import type { ResumeStore, Unfinished } from "./upload-resume";

/** Remembers in memory, as IndexedDB would (src/features/book/upload-resume.test.ts tests the real one). */
const memoryStore = (): ResumeStore & { saved: Map<string, Unfinished> } => {
  const saved = new Map<string, Unfinished>();
  return {
    saved,
    read: async (fp) => structuredClone(saved.get(fp) ?? null),
    write: async (fp, unfinished) => void saved.set(fp, structuredClone(unfinished)),
    forget: async (fp) => void saved.delete(fp),
  };
};

function setup(chosen: Chosen, partSize = 5 * MIB) {
  FakeRequest.all = [];
  const { api } = fakeUploadApi(partSize);
  const progress: UploadProgress[] = [];
  let clock = 0;
  const sleeps: number[] = [];
  const upload = new Upload(
    chosen,
    {
      api,
      resume: memoryStore(),
      request: () => new FakeRequest(),
      now: () => clock,
      sleep: async (ms) => void sleeps.push(ms),
      idempotencyKey: () => "key-1",
    },
    (p) => progress.push(p),
  );
  return { upload, api, progress, sleeps, tick: (ms: number) => (clock += ms) };
}

const oneFile = (size: number) => chosenFiles("data.bin", { "data.bin": size }, false);

describe("an upload", () => {
  it("sends each part as its own slice of its file, three at a time, then completes every file with its ETags", async () => {
    const { upload, api } = setup(
      chosenFiles("my-model", { "config.json": 1_000, "model.safetensors": 7 * MIB }),
    );
    const done = upload.start();
    await flush();
    expect(api.create).toHaveBeenCalledWith(
      "my-model",
      [
        { path: "config.json", sizeBytes: 1_000 },
        { path: "model.safetensors", sizeBytes: 7 * MIB },
      ],
      "key-1",
    );
    expect(waiting().map((r) => [r.url, r.body?.size])).toEqual([
      ["https://storage/u1/config.json/part-1", 1_000],
      ["https://storage/u1/model.safetensors/part-1", 5 * MIB],
      ["https://storage/u1/model.safetensors/part-2", 2 * MIB],
    ]);
    // Links are asked for per file, for the parts still to send.
    expect(api.partLinks).toHaveBeenCalledWith("u1", "model.safetensors", [1, 2]);
    waiting().forEach((r, i) => r.ok(`"etag-${i + 1}"`));
    expect(await done).toBe("u1");
    expect(api.complete).toHaveBeenCalledWith("u1", [
      { path: "config.json", parts: [{ number: 1, etag: '"etag-1"' }] },
      {
        path: "model.safetensors",
        parts: [
          { number: 1, etag: '"etag-2"' },
          { number: 2, etag: '"etag-3"' },
        ],
      },
    ]);
  });

  it("completes an empty file with no parts", async () => {
    const { upload, api } = setup(chosenFiles("project", { "empty.txt": 0, "main.py": 10 }));
    const done = upload.start();
    await flush();
    waiting().forEach((r) => r.ok('"e"'));
    await done;
    expect(api.complete).toHaveBeenCalledWith("u1", [
      { path: "empty.txt", parts: [] },
      { path: "main.py", parts: [{ number: 1, etag: '"e"' }] },
    ]);
  });

  it("completes a hundred files to a call", async () => {
    const sizes = Object.fromEntries(Array.from({ length: 150 }, (_, i) => [`f${i}.txt`, 0]));
    const { upload, api } = setup(chosenFiles("many", sizes));
    await upload.start();
    expect(api.complete.mock.calls.map(([, files]) => files.length)).toEqual([100, 50]);
  });

  it("never has more than three parts in flight", async () => {
    const { upload } = setup(oneFile(30 * MIB));
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
    const { upload, sleeps, progress } = setup(oneFile(4 * MIB));
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
    const { upload, api } = setup(oneFile(4 * MIB));
    const done = upload.start();
    await flush();
    waiting()[0]!.fail(403);
    await flush();
    await flush();
    expect(api.partLinks).toHaveBeenLastCalledWith("u1", "data.bin", [1]);
    expect(waiting()[0]!.url).toBe("https://storage/u1/data.bin/part-1?fresh");
    waiting()[0]!.ok('"e1"');
    await expect(done).resolves.toBe("u1");
  });

  it("counts progress from bytes storage has taken, with speed and time left, and 100% only when ready", async () => {
    const { upload, progress, tick } = setup(oneFile(10 * MIB));
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
    expect(progress.at(-1)).toMatchObject({ phase: "ready", percent: 100, resumedParts: 0 });
  });

  it("cancels: aborts every request, deletes the upload, and says it's cancelled", async () => {
    const { upload, api, progress } = setup(oneFile(12 * MIB));
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
