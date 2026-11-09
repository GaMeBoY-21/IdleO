import { describe, expect, it } from "vitest";
import { fingerprint, fromDrop, fromFolderPicker, oneFile, type DroppedEntry } from "./chosen-files";

/** A file as "Choose folder" gives it: its path from the folder chosen, in webkitRelativePath. */
const picked = (relative: string, size = 1) => {
  const file = new File([new Uint8Array(size)], relative.slice(relative.lastIndexOf("/") + 1), {
    lastModified: 7,
  });
  Object.defineProperty(file, "webkitRelativePath", { value: relative });
  return file;
};

/** A dropped folder's entries, as the browser hands them over, in batches. */
function folder(name: string, children: DroppedEntry[]): DroppedEntry {
  return {
    name,
    isFile: false,
    isDirectory: true,
    createReader: () => {
      const batches = [children.slice(0, 2), children.slice(2), []];
      return { readEntries: (resolve) => resolve(batches.shift() ?? []) };
    },
  };
}
const file = (name: string): DroppedEntry => ({
  name,
  isFile: true,
  isDirectory: false,
  file: (resolve) => resolve(new File(["x"], name)),
});

describe("the files chosen for an input", () => {
  it("names a chosen folder's upload after the folder, each file at its path inside it", () => {
    const chosen = fromFolderPicker([
      picked("my-llm-7b/tokenizer.json"),
      picked("my-llm-7b/config.json"),
      picked("my-llm-7b/.DS_Store"),
      picked("my-llm-7b/weights/model-00001-of-00002.safetensors"),
    ]);
    expect(chosen).toMatchObject({
      name: "my-llm-7b",
      folder: true,
      files: [
        { path: "config.json" },
        { path: "tokenizer.json" },
        { path: "weights/model-00001-of-00002.safetensors" },
      ],
    });
  });

  it("finds nothing in an empty folder", () => {
    expect(fromFolderPicker([])).toBeNull();
    expect(fromFolderPicker([picked("empty/.DS_Store")])).toBeNull();
  });

  it("takes one chosen file as an upload of that file", () => {
    const zip = new File(["z"], "project.zip");
    expect(oneFile(zip)).toEqual({
      name: "project.zip",
      folder: false,
      files: [{ path: "project.zip", file: zip }],
    });
  });

  it("reads a dropped folder through its entries, every batch and subfolder", async () => {
    const dropped = folder("my-model", [
      file("config.json"),
      folder("shards", [file("a.safetensors"), file("b.safetensors"), file("c.safetensors")]),
      file("tokenizer.json"),
      file("Thumbs.db"),
    ]);
    const chosen = await fromDrop(dropped, null);
    expect(chosen?.name).toBe("my-model");
    expect(chosen?.files.map(({ path }) => path)).toEqual([
      "config.json",
      "shards/a.safetensors",
      "shards/b.safetensors",
      "shards/c.safetensors",
      "tokenizer.json",
    ]);
  });

  it("takes a dropped file as itself", async () => {
    const jsonl = new File(["{}"], "examples.jsonl");
    expect(await fromDrop(file("examples.jsonl"), jsonl)).toMatchObject({
      name: "examples.jsonl",
      folder: false,
    });
  });

  it("knows the same files chosen again by name, path, size and date, without reading them", () => {
    const once = fromFolderPicker([picked("m/config.json", 10), picked("m/w.bin", 20)])!;
    const again = fromFolderPicker([picked("m/config.json", 10), picked("m/w.bin", 20)])!;
    const changed = fromFolderPicker([picked("m/config.json", 10), picked("m/w.bin", 21)])!;
    expect(fingerprint(again)).toBe(fingerprint(once));
    expect(fingerprint(changed)).not.toBe(fingerprint(once));
  });
});
