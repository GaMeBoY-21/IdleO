import { useCallback, useEffect, useRef, useState } from "react";
import { api, data } from "@/api/client";
import { totalBytes, type Chosen } from "./chosen-files";
import { Upload, type UploadApi, type UploadProgress } from "./upload";

/** The API calls an upload makes (ADR 0019, ADR 0022). */
export const uploadApi: UploadApi = {
  create: (name, files, key) =>
    data(
      api.POST("/v1/uploads", {
        params: { header: { "Idempotency-Key": key } },
        body: { name, files },
      }),
    ),
  partLinks: async (id, path, partNumbers) =>
    (
      await data(
        api.POST("/v1/uploads/{id}/part-urls", { params: { path: { id } }, body: { path, partNumbers } }),
      )
    ).parts,
  complete: (id, files) =>
    data(api.POST("/v1/uploads/{id}/complete", { params: { path: { id } }, body: { files } })),
  remove: async (id) => {
    const { response } = await api.DELETE("/v1/uploads/{id}", { params: { path: { id } } });
    return response.status;
  },
};

/** An input's upload: what it's called, its size and files, and how it stands. */
export interface InputUpload {
  name: string;
  size: number;
  fileCount: number;
  folder: boolean;
  progress: UploadProgress;
}

export const isBusy = (upload: InputUpload | null) =>
  upload !== null && ["starting", "uploading", "completing"].includes(upload.progress.phase);

/** An upload that's already ready: one from My files, a draft's, or Run again's. */
export function readyUpload(id: string, name: string, size: number, fileCount: number): InputUpload {
  return {
    name,
    size,
    fileCount,
    folder: fileCount > 1,
    progress: {
      phase: "ready",
      uploadId: id,
      sentBytes: size,
      totalBytes: size,
      percent: 100,
      bytesPerSecond: null,
      secondsLeft: 0,
      resumedParts: 0,
      error: null,
    },
  };
}

/**
 * The uploads of a booking's inputs (ADR 0022), by input name: each starts as soon as its files are chosen, so it's
 * usually done by the time the rest is answered. Choosing the same files again, or "Try again" after one stopped,
 * carries on from the last part storage took. While one is going, leaving the page asks first; leaving the booking
 * stops it and deletes what was sent (a ready one stays in My files).
 */
export function useUploads(initial: Record<string, InputUpload | null>) {
  const [uploads, setUploads] = useState<Record<string, InputUpload | null>>(initial);
  const current = useRef(new Map<string, Upload>());
  const last = useRef(new Map<string, Chosen>());
  const set = useCallback(
    (name: string, upload: InputUpload | null) => setUploads((now) => ({ ...now, [name]: upload })),
    [],
  );

  const stop = useCallback((name: string) => {
    const going = current.current.get(name);
    current.current.delete(name);
    return going?.cancel().catch(() => undefined);
  }, []);

  const choose = useCallback(
    (name: string, chosen: Chosen) => {
      void stop(name);
      last.current.set(name, chosen);
      const about = {
        name: chosen.name,
        size: totalBytes(chosen),
        fileCount: chosen.files.length,
        folder: chosen.folder,
      };
      const started = new Upload(chosen, { api: uploadApi }, (progress) => {
        if (current.current.get(name) === started) {
          set(name, { ...about, progress });
        }
      });
      current.current.set(name, started);
      // A failure shows in the upload's progress; a cancel is what was asked for.
      started.start().catch(() => undefined);
    },
    [stop, set],
  );

  /** The files that stopped, sent again from where storage left off. */
  const retry = useCallback(
    (name: string) => {
      const chosen = last.current.get(name);
      if (chosen) {
        choose(name, chosen);
      }
    },
    [choose],
  );

  /** A ready upload picked from My files: nothing to send. */
  const pick = useCallback(
    (name: string, ready: InputUpload) => {
      void stop(name);
      last.current.delete(name);
      set(name, ready);
    },
    [stop, set],
  );

  /** Stops an upload that's going and deletes what was sent; a ready one is only taken off this booking. */
  const remove = useCallback(
    async (name: string) => {
      last.current.delete(name);
      set(name, null);
      await stop(name);
    },
    [stop, set],
  );

  const going = Object.values(uploads).some(isBusy);
  useEffect(() => {
    if (!going) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [going]);

  useEffect(() => {
    const uploading = current.current;
    return () => uploading.forEach((upload) => void upload.cancel().catch(() => undefined));
  }, []);

  return { uploads, choose, retry, pick, remove };
}
