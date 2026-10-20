import type { components } from "@/api/schema.gen";
import { formatBytes } from "./bytes";

type Session = components["schemas"]["SessionResponse"];

/** One of a session's inputs, as the session holds it: an upload of the renter's, or a link. */
export interface GivenInput {
  name: string;
  uploadId: string | null;
  url: string | null;
}

/** Every input a session was booked with, in the order it holds them (a fine-tune has a model and its data). */
export function sessionInputs(session: Session): GivenInput[] {
  return Object.entries(session.inputs).map(([name, input]) => ({
    name,
    uploadId: input.uploadId ?? null,
    url: input.url ?? null,
  }));
}

/**
 * One input as the session screen shows it: its label from the catalog, and, once its upload has been read, its size
 * and files. A link has neither; an upload that hasn't been read yet has `bytes` null.
 */
export interface InputFact {
  name: string;
  label: string;
  link: boolean;
  /** The upload's name, once read. */
  filename: string | null;
  bytes: number | null;
  files: number | null;
}

const filesOf = (count: number) => `${count} ${count === 1 ? "file" : "files"}`;

/** "4.2 GB · 9 files", "68 KB", "From your link"; "Uploaded" while the upload isn't read yet. */
export function inputLine(fact: InputFact): string {
  if (fact.link) {
    return "From your link";
  }
  if (fact.bytes === null) {
    return "Uploaded";
  }
  return fact.files !== null && fact.files > 1
    ? `${formatBytes(fact.bytes)} · ${filesOf(fact.files)}`
    : formatBytes(fact.bytes);
}

/**
 * What all the uploaded inputs come to together: "4.2 GB · 9 files", with "and a link" or "and 2 links" for any that
 * are links. Only links: "From your link" (or "your links"). Null while an upload's size isn't read yet.
 */
export function inputsTotal(facts: InputFact[]): string | null {
  const uploads = facts.filter((fact) => !fact.link);
  const links = facts.length - uploads.length;
  if (facts.length === 0 || uploads.some((fact) => fact.bytes === null)) {
    return null;
  }
  if (uploads.length === 0) {
    return links === 1 ? "From your link" : "From your links";
  }
  const bytes = uploads.reduce((sum, fact) => sum + (fact.bytes ?? 0), 0);
  const files = uploads.reduce((sum, fact) => sum + (fact.files ?? 1), 0);
  const total = files > 1 ? `${formatBytes(bytes)} · ${filesOf(files)}` : formatBytes(bytes);
  return links === 0 ? total : `${total} · and ${links === 1 ? "a link" : `${links} links`}`;
}
