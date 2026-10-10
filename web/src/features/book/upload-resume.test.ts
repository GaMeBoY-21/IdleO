import "fake-indexeddb/auto";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { chosenFiles, fakeUploadApi, FakeRequest, MIB, waiting } from "@/test/upload-fakes";
import { fingerprint } from "./chosen-files";
import { Upload, type UploadProgress } from "./upload";
import { browserResumeStore } from "./upload-resume";

/** A model folder of two files: five parts of 5 MiB, the last one 2 MiB. */
const model = () => chosenFiles("my-llm", { "config.json": 3 * MIB, "model.safetensors": 14 * MIB });

function start(api: ReturnType<typeof fakeUploadApi>["api"], keys: string[]) {
  const progress: UploadProgress[] = [];
  const upload = new Upload(
    model(),
    {
      api,
      request: () => new FakeRequest(),
      idempotencyKey: () => keys.shift()!,
      sleep: async () => undefined,
    },
    (p) => progress.push(p),
  );
  return { done: upload.start(), progress, upload };
}

const remembered = () => browserResumeStore.read(fingerprint(model()));

beforeEach(async () => {
  FakeRequest.all = [];
  await browserResumeStore.forget(fingerprint(model()));
});

describe("an interrupted upload, in IndexedDB", () => {
  it("carries on after a reload from the last part storage took, with the same upload and key", async () => {
    const { api } = fakeUploadApi();
    // The first try: two of the model's parts go through, then the page is reloaded.
    const first = start(api, ["key-1", "key-2"]);
    void first.done.catch(() => undefined);
    await vi.waitFor(() => expect(waiting()).toHaveLength(3));
    const [config, part1] = waiting();
    config!.ok('"c1"');
    part1!.ok('"m1"');
    await vi.waitFor(async () =>
      expect((await remembered())?.parts).toEqual({
        "config.json": { 1: '"c1"' },
        "model.safetensors": { 1: '"m1"' },
      }),
    );

    // After the reload, the same files are chosen again (new File objects, same names, sizes and dates).
    FakeRequest.all = [];
    const second = start(api, ["key-3"]);
    await vi.waitFor(() => expect(waiting()).toHaveLength(2));
    expect(api.create).toHaveBeenLastCalledWith("my-llm", expect.any(Array), "key-1");
    // Only the parts storage hasn't taken, through links signed afresh.
    expect(waiting().map((r) => r.url)).toEqual([
      "https://storage/u1/model.safetensors/part-2?fresh",
      "https://storage/u1/model.safetensors/part-3?fresh",
    ]);
    // What storage already had counts from the start: 3 MiB and 5 MiB of 17.
    expect(second.progress.find((p) => p.phase === "uploading")).toMatchObject({
      resumedParts: 2,
      sentBytes: 8 * MIB,
      percent: 47,
    });
    waiting().forEach((r, i) => r.ok(`"m${i + 2}"`));
    await expect(second.done).resolves.toBe("u1");
    expect(api.complete).toHaveBeenCalledWith("u1", [
      { path: "config.json", parts: [{ number: 1, etag: '"c1"' }] },
      {
        path: "model.safetensors",
        parts: [
          { number: 1, etag: '"m1"' },
          { number: 2, etag: '"m2"' },
          { number: 3, etag: '"m3"' },
        ],
      },
    ]);
    // Ready: forgotten, so choosing the files again starts a new upload.
    expect(await remembered()).toBeNull();
  });

  it("starts over when the upload it remembers is gone", async () => {
    const { api, setStatus } = fakeUploadApi();
    const first = start(api, ["key-a"]);
    void first.done.catch(() => undefined);
    await vi.waitFor(() => expect(waiting()).toHaveLength(3));
    waiting()[0]!.ok('"c1"');
    await vi.waitFor(async () => expect((await remembered())?.uploadId).toBe("u1"));
    // Deleted in My files, say, or expired.
    setStatus("u1", "deleted");

    FakeRequest.all = [];
    const second = start(api, ["key-b"]);
    await vi.waitFor(() => expect(waiting()).toHaveLength(3));
    expect(api.create).toHaveBeenLastCalledWith("my-llm", expect.any(Array), "key-b");
    expect(waiting()[0]!.url).toBe("https://storage/u2/config.json/part-1");
    expect((await remembered())?.uploadId).toBe("u2");
    void second.done.catch(() => undefined);
    await second.upload.cancel();
  });

  it("forgets a cancelled upload", async () => {
    const { api } = fakeUploadApi();
    const { done, upload } = start(api, ["key-x"]);
    void done.catch(() => undefined);
    await vi.waitFor(async () => expect(await remembered()).not.toBeNull());
    await upload.cancel();
    expect(await remembered()).toBeNull();
  });
});
