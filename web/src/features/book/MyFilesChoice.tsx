import { Link } from "wouter";
import { useUploadPages, type UploadInfo } from "@/api/queries";
import { Button, buttonClasses } from "@/components/ui/Button";
import { Option } from "@/components/ui/Option";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { useNow } from "@/lib/clock";
import { cn } from "@/lib/cn";
import { uploadFacts, uploadName } from "@/lib/uploads";
import type { TemplateInput } from "./steps";

/** Whether a ready upload can be this input: one file for a file input, and no bigger than it takes. */
export const fits = (input: TemplateInput, upload: UploadInfo) =>
  upload.status === "ready" &&
  (input.kind === "folder" || upload.fileCount === 1) &&
  (input.maxSizeBytes == null || upload.sizeBytes <= input.maxSizeBytes);

/**
 * "From My files" (ADR 0022): the renter's ready uploads this input can take, newest first; picking one uses it as
 * it is, with nothing to send. My files itself, to tidy up, is a link away.
 */
export function MyFilesChoice({
  input,
  chosenId,
  onPick,
}: {
  input: TemplateInput;
  chosenId: string | null;
  onPick: (upload: UploadInfo) => void;
}) {
  const pages = useUploadPages();
  const now = useNow("half-minute");
  const uploads = pages.data?.pages.flatMap((page) => page.uploads).filter((upload) => fits(input, upload));
  const manage = (
    <Link href="/files/" className={cn(buttonClasses("quiet"), "self-start")}>
      Manage My files
    </Link>
  );

  if (!uploads) {
    return pages.isError ? (
      <ErrorState
        error={pages.error}
        what="your files"
        onRetry={() => void pages.refetch()}
        retrying={pages.isFetching}
      />
    ) : (
      <div role="status" aria-label="Loading your files" className="flex flex-col gap-2">
        {[0, 1].map((key) => (
          <div
            key={key}
            className="flex flex-col gap-0.5 rounded-control border-2 border-transparent px-4 py-3.5"
          >
            <SkeletonLine size="option" width="w-1/2" />
            <SkeletonLine size="small" width="w-2/3" />
          </div>
        ))}
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      {uploads.length === 0 ? (
        <p className="text-small text-text-2">
          Nothing in My files fits here yet. Upload it once, and it&apos;s kept for your next sessions.
        </p>
      ) : (
        <div role="group" aria-label={`${input.label} from My files`} className="flex flex-col gap-2">
          {uploads.map((upload) => (
            <Option
              key={upload.id}
              title={uploadName(upload)}
              detail={<span className="tabular">{uploadFacts(upload, now)}</span>}
              selected={upload.id === chosenId}
              onClick={() => onPick(upload)}
            />
          ))}
        </div>
      )}
      {pages.hasNextPage && (
        <Button
          variant="quiet"
          className="self-start"
          busy={pages.isFetchingNextPage}
          onClick={() => void pages.fetchNextPage()}
        >
          Show more
        </Button>
      )}
      {manage}
    </div>
  );
}
