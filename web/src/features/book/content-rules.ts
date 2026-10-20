import type { Template } from "@/api/queries";
import type { Chosen } from "./chosen-files";

type Input = Template["inputs"][number];
type Rule = Input["mustContain"][number];

/**
 * What an upload must hold, checked in the browser as soon as the files are chosen, in the words the catalog gives
 * (ADR 0022). The server checks the same rules again when the session is booked or quoted (`input-not-usable`), with the
 * same words from the same catalog; this is only so the renter hears it before the upload is done. It mirrors
 * `Template.ContentRule` and `InputContent` in the control plane.
 */

/** What the renter reads when a zip can't be read; the control plane's words for the same case. */
export const UNREADABLE_ZIP =
  "We couldn't read that zip. Check that it opens on your computer, then upload it again.";

/** Whether the rule looks for the file at `path` (relative, with forward slashes). */
export function matches(rule: Rule, path: string): boolean {
  const segments = path.split("/");
  if (rule.maxDepth != null && segments.length > rule.maxDepth) {
    return false;
  }
  const name = segments[segments.length - 1]!.toLowerCase();
  return rule.anyOf.some((pattern) => {
    const wanted = pattern.toLowerCase();
    return wanted.startsWith("*") ? name.endsWith(wanted.slice(1)) : name === wanted;
  });
}

/** The words for the first rule no path meets; null when they all do, or the input has no rules. */
export function problem(input: Input, paths: string[]): string | null {
  return input.mustContain.find((rule) => !paths.some((path) => matches(rule, path)))?.message ?? null;
}

const MOST_DIRECTORY_BYTES = 8 << 20;

/** The file names in a zip, read from its central directory alone (the end of the file, then the directory). */
export async function zipNames(file: Blob): Promise<string[]> {
  const read = async (from: number, length: number) =>
    new DataView(await file.slice(from, from + length).arrayBuffer());
  const size = file.size;
  if (size < 22) {
    throw new Error("too small to be a zip");
  }
  const tailLength = Math.min(size, 22 + 65_535);
  const tail = await read(size - tailLength, tailLength);
  let end = -1;
  for (let at = tailLength - 22; at >= 0; at--) {
    if (tail.getUint32(at, true) === 0x06054b50) {
      end = at;
      break;
    }
  }
  if (end < 0) {
    throw new Error("no end of central directory");
  }
  let entries = tail.getUint16(end + 10, true);
  let length = tail.getUint32(end + 12, true);
  let offset = tail.getUint32(end + 16, true);
  if (entries === 0xffff || length === 0xffffffff || offset === 0xffffffff) {
    if (end < 20 || tail.getUint32(end - 20, true) !== 0x07064b50) {
      throw new Error("a zip64 zip without its locator");
    }
    const record64 = Number(tail.getBigUint64(end - 20 + 8, true));
    if (record64 + 56 > size) {
      throw new Error("the zip64 end record is outside the file");
    }
    const record = await read(record64, 56);
    if (record.getUint32(0, true) !== 0x06064b50) {
      throw new Error("the zip64 end record isn't there");
    }
    entries = Number(record.getBigUint64(32, true));
    length = Number(record.getBigUint64(40, true));
    offset = Number(record.getBigUint64(48, true));
  }
  if (offset + length > size || length > MOST_DIRECTORY_BYTES) {
    throw new Error("the central directory is outside the file, or too large");
  }
  const directory = await read(offset, length);
  const bytes = new Uint8Array(directory.buffer);
  const names: string[] = [];
  let at = 0;
  for (let seen = 0; seen < entries; seen++) {
    if (at + 46 > bytes.length || directory.getUint32(at, true) !== 0x02014b50) {
      throw new Error("the central directory is damaged");
    }
    const nameLength = directory.getUint16(at + 28, true);
    const extraLength = directory.getUint16(at + 30, true);
    const commentLength = directory.getUint16(at + 32, true);
    if (at + 46 + nameLength > bytes.length) {
      throw new Error("the central directory is damaged");
    }
    const name = new TextDecoder()
      .decode(bytes.subarray(at + 46, at + 46 + nameLength))
      .replaceAll("\\", "/");
    at += 46 + nameLength + extraLength + commentLength;
    if (!name.endsWith("/") && !name.startsWith("__MACOSX/") && !name.endsWith(".DS_Store")) {
      names.push(name);
    }
  }
  return names;
}

/** A folder input given one zip is checked by what the zip holds. */
const isZip = (input: Input, chosen: Chosen) =>
  input.kind === "folder" &&
  chosen.files.length === 1 &&
  chosen.files[0]!.path.toLowerCase().endsWith(".zip");

/** The words an input's rules have for what was chosen, or null. Only an input that takes an upload has rules. */
export async function inspect(input: Input, chosen: Chosen): Promise<string | null> {
  if (input.mustContain.length === 0) {
    return null;
  }
  if (isZip(input, chosen)) {
    try {
      return problem(input, await zipNames(chosen.files[0]!.file));
    } catch {
      return UNREADABLE_ZIP;
    }
  }
  return problem(
    input,
    chosen.files.map(({ path }) => path),
  );
}
