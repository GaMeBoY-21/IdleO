import { Link } from "wouter";
import { useUploadPages } from "@/api/queries";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Button, buttonClasses } from "@/components/ui/Button";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { useNow } from "@/lib/clock";
import { useTitle } from "@/lib/title";
import { FIT_FRAME } from "@/components/shell/Layout";
import { PageHeading } from "@/components/ui/Heading";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import type { components } from "@/api/schema.gen";
import { formatBytes } from "@/lib/bytes";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { formatDay } from "@/lib/time";
import { FileRow } from "./FileRow";

/**
 * My files (ADR 0022): the folders and files the renter has uploaded, kept to use again in later sessions until 30
 * days after a session last used them. Name, size, files, when last used, and Delete. Reached from the account menu
 * and from an input's "From My files" on Book.
 */
export function MyFiles() {
  useTitle("My files");
  const pages = useUploadPages();
  const now = useNow("half-minute");
  const uploads = pages.data?.pages.flatMap((page) => page.uploads);
  const since = staleSince(pages);

  const wide = useWide();
  const body = (
    <>
      {since !== null && <StaleNotice updatedAt={since} />}
      <p className="text-body text-text-2">
        What you upload when you book is kept here, to use again. Each is deleted 30 days after a session last
        used it; you can delete it sooner.
      </p>
      {uploads ? (
        uploads.length === 0 ? (
          <EmptyState
            title="Nothing here yet"
            action={
              <Link href="/book/" className={buttonClasses("primary")}>
                Book a GPU
              </Link>
            }
          >
            Your model, data or code, uploaded when you book a GPU, stays here for your next session.
          </EmptyState>
        ) : (
          <div className="flex flex-col gap-4">
            <Tile as="div" className="px-4 py-0">
              <ul aria-label="Your files" className="flex flex-col divide-y divide-line">
                {uploads.map((upload) => (
                  <FileRow key={upload.id} upload={upload} now={now} />
                ))}
              </ul>
            </Tile>
            {pages.hasNextPage && (
              <Button
                variant="ghost"
                className="self-center"
                busy={pages.isFetchingNextPage}
                onClick={() => void pages.fetchNextPage()}
              >
                Show more
              </Button>
            )}
            {pages.isFetchNextPageError && (
              <p className="text-small text-danger" role="alert">
                We couldn&apos;t load more of your files. Try again.
              </p>
            )}
          </div>
        )
      ) : pages.isError ? (
        <ErrorState
          error={pages.error}
          what="your files"
          onRetry={() => void pages.refetch()}
          retrying={pages.isFetching}
        />
      ) : (
        <Tile
          as="div"
          className="flex flex-col divide-y divide-line px-4 py-0"
          role="status"
          aria-label="Loading your files"
        >
          {[0, 1, 2].map((key) => (
            <div key={key} className="flex items-center gap-4 py-4">
              <span aria-hidden="true" className="size-5.5 shrink-0" />
              <div className="flex flex-1 flex-col gap-1">
                <SkeletonLine size="option" width="w-1/2" />
                <SkeletonLine size="small" width="w-2/3" />
              </div>
              <SkeletonLine size="body" width="w-14" />
            </div>
          ))}
        </Tile>
      )}
    </>
  );
  if (!wide) {
    return <AccountFrame title="My files">{body}</AccountFrame>;
  }
  // From 768 px a tab of its own (ADR 0058): the list beside what's kept, how much, and when the next one goes.
  return (
    <div className={cn(FIT_FRAME, "flex flex-col gap-6")}>
      <PageHeading>My files</PageHeading>
      <div className="grid items-start gap-4 xl:grid-cols-3">
        <div className="flex flex-col gap-4 xl:col-span-2">{body}</div>
        <FilesSummary uploads={uploads} />
      </div>
    </div>
  );
}

type Upload = components["schemas"]["UploadResponse"];

/** What's kept, in sum: how many, how big, and which goes next, and when; then how to use one again. */
function FilesSummary({ uploads }: { uploads: Upload[] | undefined }) {
  const next = uploads ? [...uploads].sort((a, b) => a.expiresAt.localeCompare(b.expiresAt))[0] : undefined;
  return (
    <Tile as="section" aria-labelledby="files-summary-title" className="flex flex-col gap-2 xl:p-8">
      <SectionTitle id="files-summary-title">Kept for you</SectionTitle>
      <dl className="grid grid-cols-2 gap-2">
        <div className="flex min-h-18 flex-col justify-center rounded-control bg-panel-2 px-4 py-4">
          <dt className="text-small text-text-2">Uploads</dt>
          <dd className="text-option font-semibold tabular">{uploads ? uploads.length : "…"}</dd>
        </div>
        <div className="flex min-h-18 flex-col justify-center rounded-control bg-panel-2 px-4 py-4">
          <dt className="text-small text-text-2">In all</dt>
          <dd className="text-option font-semibold tabular">
            {uploads ? formatBytes(uploads.reduce((sum, upload) => sum + upload.sizeBytes, 0)) : "…"}
          </dd>
        </div>
      </dl>
      <p className="min-h-10 text-small text-text-2">
        {next
          ? `${next.name} goes next, on ${formatDay(next.expiresAt)}, unless a session uses it before then.`
          : "Nothing is due to go."}
      </p>
      <p className="text-small text-text-2">
        To use one again, choose From My files for an input on Book, or Run again from a session.
      </p>
    </Tile>
  );
}
