import type { BookValues, Uploads } from "./form";

/** A ready upload, as a draft keeps it: enough to show it again without asking. */
export interface KeptUpload {
  id: string;
  name: string;
  size: number;
  fileCount: number;
}

/**
 * The form's draft, kept per template in this tab (sessionStorage), so a reload or a trip back keeps what was
 * answered, with each input's ready upload; forgotten once booked. Storage can be missing or full; then there's
 * simply no draft. An upload still going isn't kept here: choosing its files again resumes it (upload-resume.ts).
 */
export interface Draft {
  values: BookValues;
  uploads: Record<string, KeptUpload | null>;
}

const draftKey = (templateId: string) => `idleo.book-draft.${templateId}`;

/** The ready uploads of a form, as a draft keeps them. */
export function keptUploads(uploads: Uploads): Draft["uploads"] {
  return Object.fromEntries(
    Object.entries(uploads).map(([name, upload]) => [
      name,
      upload?.progress.phase === "ready" && upload.progress.uploadId !== null
        ? { id: upload.progress.uploadId, name: upload.name, size: upload.size, fileCount: upload.fileCount }
        : null,
    ]),
  );
}

export function readDraft(templateId: string): Draft | null {
  try {
    const text = sessionStorage.getItem(draftKey(templateId));
    const draft = text ? (JSON.parse(text) as Partial<Draft>) : null;
    // A draft from before inputs had names has no uploads by name: it's dropped rather than half read.
    return draft?.values?.inputs && draft.uploads ? (draft as Draft) : null;
  } catch {
    return null;
  }
}

export function saveDraft(templateId: string, draft: Draft): void {
  try {
    sessionStorage.setItem(draftKey(templateId), JSON.stringify(draft));
  } catch {
    // No room, or no storage: the draft just isn't kept.
  }
}

export function forgetDraft(templateId: string): void {
  try {
    sessionStorage.removeItem(draftKey(templateId));
  } catch {
    // Nothing to forget.
  }
}
