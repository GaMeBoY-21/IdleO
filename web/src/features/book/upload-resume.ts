import { createStore, del, get, set, type UseStore } from "idb-keyval";

/**
 * Where an unfinished upload is remembered, so choosing the same files again carries on from the last part storage
 * took instead of starting over (ADR 0022): after a reload, a dropped connection or a closed laptop. Kept in this
 * browser's IndexedDB, by the files' fingerprint (src/features/book/chosen-files.ts); forgotten once the upload is
 * ready, cancelled or gone. Storage can be missing, full or blocked (a private window): then nothing is remembered,
 * and the upload simply starts over next time.
 */
export interface Unfinished {
  uploadId: string;
  /** The Idempotency-Key it was opened with: opening it again with the same key returns it as it stands. */
  key: string;
  /** Each file's parts storage took, by path: part number to ETag. */
  parts: Record<string, Record<number, string>>;
}

export interface ResumeStore {
  read(fingerprint: string): Promise<Unfinished | null>;
  write(fingerprint: string, unfinished: Unfinished): Promise<void>;
  forget(fingerprint: string): Promise<void>;
}

let store: UseStore | null = null;
const opened = () => (store ??= createStore("idleo-uploads", "unfinished"));

/** The browser's IndexedDB; every failure is swallowed, as remembering is a convenience. */
export const browserResumeStore: ResumeStore = {
  async read(fingerprint) {
    try {
      return (await get<Unfinished>(fingerprint, opened())) ?? null;
    } catch {
      return null;
    }
  },
  async write(fingerprint, unfinished) {
    try {
      await set(fingerprint, unfinished, opened());
    } catch {
      // Not remembered: the next try starts over.
    }
  },
  async forget(fingerprint) {
    try {
      await del(fingerprint, opened());
    } catch {
      // Nothing to forget, or no storage.
    }
  },
};
