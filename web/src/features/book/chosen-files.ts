/**
 * What the renter chose for an input (ADR 0022): one file, or a folder of files with their paths inside it, such as
 * a model folder (`config.json`, `tokenizer.json`, `model-00001-of-00004.safetensors`…). An upload is named after the
 * folder, or the file, and holds each file at its relative path.
 */
export interface ChosenFile {
  /** Relative to the folder, with forward slashes: `config.json`, `checkpoints/step-100/adapter.bin`. */
  path: string;
  file: File;
}

export interface Chosen {
  /** What My files calls it: the folder's name, or the file's. */
  name: string;
  files: ChosenFile[];
  folder: boolean;
}

/** The most files an upload holds, as the API allows. */
export const MAX_FILES = 1_000;

/** Files the operating system leaves in folders, never part of a model or a project. */
const LEFT_BY_THE_SYSTEM = new Set([".DS_Store", "Thumbs.db", "desktop.ini"]);
const kept = (path: string) => !LEFT_BY_THE_SYSTEM.has(path.slice(path.lastIndexOf("/") + 1));

export const totalBytes = (chosen: Chosen) => chosen.files.reduce((sum, { file }) => sum + file.size, 0);

/** One file, from "Choose file" or dropped on its own. */
export const oneFile = (file: File): Chosen => ({
  name: file.name,
  files: [{ path: file.name, file }],
  folder: false,
});

/**
 * A folder from "Choose folder" (`webkitdirectory`): each file's `webkitRelativePath` starts with the folder's name
 * (`my-llm-7b/config.json`), which names the upload; the rest is the file's path in it. Null for an empty folder.
 */
export function fromFolderPicker(list: ArrayLike<File>): Chosen | null {
  const files = Array.from(list);
  const first = files[0]?.webkitRelativePath ?? "";
  const name = first.split("/")[0] ?? "";
  if (name === "") {
    return files.length === 1 ? oneFile(files[0]!) : null;
  }
  const chosen = files
    .map((file) => ({ path: file.webkitRelativePath.slice(name.length + 1), file }))
    .filter(({ path }) => path !== "" && kept(path))
    .sort((a, b) => a.path.localeCompare(b.path));
  return chosen.length === 0 ? null : { name, files: chosen, folder: true };
}

/** The part of a dropped item's FileSystemEntry a drop reads, so tests can stand in for it. */
export interface DroppedEntry {
  name: string;
  isFile: boolean;
  isDirectory: boolean;
  file?: (resolve: (file: File) => void, reject: (error: unknown) => void) => void;
  createReader?: () => {
    readEntries: (resolve: (entries: DroppedEntry[]) => void, reject: (error: unknown) => void) => void;
  };
}

/** Every file under a dropped folder, with its path below it. */
async function walk(entry: DroppedEntry, prefix: string, into: ChosenFile[]): Promise<void> {
  if (entry.isFile && entry.file) {
    const file = await new Promise<File>((resolve, reject) => entry.file!(resolve, reject));
    into.push({ path: `${prefix}${entry.name}`, file });
    return;
  }
  if (!entry.isDirectory || !entry.createReader) {
    return;
  }
  const reader = entry.createReader();
  // readEntries gives a batch at a time (100 in Chrome), and an empty one at the end.
  for (;;) {
    const batch = await new Promise<DroppedEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
    if (batch.length === 0) {
      return;
    }
    for (const child of batch) {
      await walk(child, `${prefix}${entry.name}/`, into);
    }
  }
}

/**
 * What was dropped on an input: a folder (read through its entries, as the browser offers them), or a file. Only
 * the first item counts, as an input is one upload. Null when nothing usable was dropped.
 */
export async function fromDrop(entry: DroppedEntry | null, file: File | null): Promise<Chosen | null> {
  if (entry?.isDirectory) {
    const files: ChosenFile[] = [];
    await walk(entry, "", files);
    const inside = files
      .map(({ path, file }) => ({ path: path.slice(entry.name.length + 1), file }))
      .filter(({ path }) => kept(path))
      .sort((a, b) => a.path.localeCompare(b.path));
    return inside.length === 0 ? null : { name: entry.name, files: inside, folder: true };
  }
  return file ? oneFile(file) : null;
}

/**
 * The same files chosen again: a resumed upload is found by this (src/features/book/upload-resume.ts). Name, path,
 * size and last change of every file; never their contents, which would mean reading gigabytes.
 */
export function fingerprint(chosen: Chosen): string {
  return JSON.stringify([
    chosen.name,
    chosen.files.map(({ path, file }) => [path, file.size, file.lastModified]),
  ]);
}
