import { useMutation, useQueryClient } from "@tanstack/react-query";
import { FileText, Folder } from "lucide-react";
import { useState } from "react";
import { api } from "@/api/client";
import { ApiError } from "@/api/errors";
import { keys, type UploadInfo } from "@/api/queries";
import { Button } from "@/components/ui/Button";
import { problemMessage } from "@/lib/problems";
import { uploadFacts, uploadName } from "@/lib/uploads";

/** `DELETE /v1/uploads/{id}`: a 204, or the problem (upload-in-use while a session that hasn't ended uses it). */
async function deleteUpload(id: string): Promise<void> {
  const { error, response } = await api.DELETE("/v1/uploads/{id}", { params: { path: { id } } });
  if (!response.ok) {
    throw ApiError.of(response.status, error);
  }
}

/**
 * One upload in My files: its name, size, files and when it was last used, and Delete. Deleting can't be undone, so
 * it asks once more in the row itself, not in a dialog (web/DESIGN.md principle 5).
 */
export function FileRow({ upload, now }: { upload: UploadInfo; now: number }) {
  const client = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const remove = useMutation({
    mutationFn: () => deleteUpload(upload.id),
    onSuccess: () => {
      client.removeQueries({ queryKey: ["upload", upload.id] });
      return client.invalidateQueries({ queryKey: keys.uploads });
    },
    onSettled: () => setConfirming(false),
  });
  const name = uploadName(upload);
  const Icon = upload.fileCount > 1 ? Folder : FileText;

  return (
    <li className="flex flex-col gap-1 py-4">
      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-2">
        <Icon aria-hidden="true" className="size-5.5 shrink-0" strokeWidth={1.75} />
        <div className="flex min-w-0 flex-1 basis-menu flex-col gap-0.5">
          <p className="text-option font-semibold break-words">{name}</p>
          <p className="text-small text-text-muted tabular">{uploadFacts(upload, now)}</p>
        </div>
        {confirming ? (
          <span className="flex shrink-0 items-center gap-1">
            <Button variant="quiet" onClick={() => setConfirming(false)}>
              Keep
            </Button>
            <Button variant="danger" busy={remove.isPending} onClick={() => remove.mutate()}>
              Delete for good
            </Button>
          </span>
        ) : (
          <Button variant="quiet" aria-label={`Delete ${name}`} onClick={() => setConfirming(true)}>
            Delete
          </Button>
        )}
      </div>
      {remove.isError && (
        <p className="text-caption text-danger" role="alert">
          {problemMessage(remove.error)}
        </p>
      )}
    </li>
  );
}
