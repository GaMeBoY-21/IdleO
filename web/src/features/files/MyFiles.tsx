import { Link } from "wouter";
import { useUploadPages } from "@/api/queries";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Tile } from "@/components/ui/Tile";
import { Button, buttonClasses } from "@/components/ui/Button";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { useNow } from "@/lib/clock";
import { useTitle } from "@/lib/title";
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

  return (
    <AccountFrame title="My files">
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
            <Tile as="div" className="px-4.5 py-0">
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
          className="flex flex-col divide-y divide-line px-4.5 py-0"
          role="status"
          aria-label="Loading your files"
        >
          {[0, 1, 2].map((key) => (
            <div key={key} className="flex items-center gap-3.5 py-4">
              <span aria-hidden="true" className="size-5.5 shrink-0" />
              <div className="flex flex-1 flex-col gap-0.5">
                <SkeletonLine size="option" width="w-1/2" />
                <SkeletonLine size="small" width="w-2/3" />
              </div>
              <SkeletonLine size="body" width="w-14" />
            </div>
          ))}
        </Tile>
      )}
    </AccountFrame>
  );
}
