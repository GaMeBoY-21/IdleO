import { useCallback, useEffect, useRef, useState } from "react";
import { api, data } from "@/api/client";
import { Upload, type UploadApi, type UploadProgress } from "./upload";

/** The API calls an upload makes (ADR 0019). */
export const uploadApi: UploadApi = {
  create: (filename, sizeBytes, key) =>
    data(
      api.POST("/v1/uploads", {
        params: { header: { "Idempotency-Key": key } },
        body: { filename, sizeBytes },
      }),
    ),
  partLinks: async (id, partNumbers) =>
    (await data(api.POST("/v1/uploads/{id}/part-urls", { params: { path: { id } }, body: { partNumbers } })))
      .parts,
  complete: (id, parts) =>
    data(api.POST("/v1/uploads/{id}/complete", { params: { path: { id } }, body: { parts } })),
  remove: async (id) => {
    const { response } = await api.DELETE("/v1/uploads/{id}", { params: { path: { id } } });
    return response.status;
  },
};

/** The file chosen as a session's input, and how its upload stands. */
export interface ChosenFile {
  name: string;
  size: number;
  progress: UploadProgress;
}

const busy = (file: ChosenFile | null) =>
  file !== null && ["starting", "uploading", "completing"].includes(file.progress.phase);

/** An upload kept from earlier (a draft, or Run again), already ready. */
export function readyFile(id: string, name: string, size: number): ChosenFile {
  return {
    name,
    size,
    progress: {
      phase: "ready",
      uploadId: id,
      sentBytes: size,
      totalBytes: size,
      percent: 100,
      bytesPerSecond: null,
      secondsLeft: 0,
      error: null,
    },
  };
}

/**
 * The upload of the booking's input: it starts as soon as a file is chosen, so it's usually done by review. While
 * it's going, leaving the page asks first; leaving the booking cancels it, and deletes what was sent.
 */
export function useUpload(initial: ChosenFile | null) {
  const [file, setFile] = useState<ChosenFile | null>(initial);
  const current = useRef<Upload | null>(null);

  const choose = useCallback((chosen: File) => {
    void current.current?.cancel().catch(() => undefined);
    const upload = new Upload(chosen, { api: uploadApi }, (progress) =>
      setFile((now) =>
        current.current === upload ? { name: chosen.name, size: chosen.size, progress } : now,
      ),
    );
    current.current = upload;
    // A failure shows in the file's progress; a cancel is what was asked for.
    upload.start().catch(() => undefined);
  }, []);

  /** Stops an upload that's going and deletes what was sent; a ready one is only taken off this booking. */
  const remove = useCallback(async () => {
    const upload = current.current;
    current.current = null;
    setFile(null);
    await upload?.cancel();
  }, []);

  const going = busy(file);
  useEffect(() => {
    if (!going) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [going]);

  useEffect(
    () => () => {
      void current.current?.cancel().catch(() => undefined);
    },
    [],
  );

  return { file, choose, remove, uploading: going };
}
