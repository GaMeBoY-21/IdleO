import { ChevronLeft } from "lucide-react";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { useImages, useRemoveImage, useVulnerabilities, type Image } from "@/api/images";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, errorSentence, StaleNotice, staleSince } from "@/components/ui/States";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { ImageFindings } from "@/features/images/ImageFindings";
import { useAfterPaint } from "@/lib/after-first-screen";
import { useTitle } from "@/lib/title";

const ROW = "flex min-h-24 flex-col gap-2 py-4";

/**
 * Account → My images (ADR 0042): every image the person has checked, newest first, with what each check found, and
 * Remove. `?id=` also lists one image's known vulnerabilities, which the warning links to. A private image's token is
 * never shown; removing the image removes it too, unless another image uses it.
 */
export function MyImages() {
  useTitle("My images");
  const painted = useAfterPaint();
  const images = useImages(painted);
  const since = staleSince(images);
  const id = new URLSearchParams(useSearch()).get("id");

  return (
    <AccountFrame title="My images">
      <Link
        href="/account/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-muted underline md:hidden"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Account
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <p className="max-w-prose text-small text-text-muted">
        Your own container images, checked before they can reach anyone&apos;s machine. Book one from Book,
        Run your code, Use my own image.
      </p>
      {images.data && !images.data.canUse && (
        <Banner tone="info">
          {images.data.whyNot}{" "}
          <Link href="/hosting/payout-details/" className="font-semibold underline">
            Give your PAN
          </Link>
        </Banner>
      )}
      <Tile as="section" aria-labelledby="images-title" className="flex flex-col gap-1">
        <SectionTitle id="images-title">Your images</SectionTitle>
        {images.data ? (
          images.data.images.length === 0 ? (
            <EmptyState title="No images yet">
              Check one from Book, Run your code, Use my own image. It will be listed here.
            </EmptyState>
          ) : (
            <ul className="flex flex-col divide-y divide-divider">
              {images.data.images.map((image) => (
                <ImageItem key={image.id} image={image} />
              ))}
            </ul>
          )
        ) : images.isError ? (
          <ErrorState
            error={images.error}
            what="your images"
            onRetry={() => void images.refetch()}
            retrying={images.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-divider"
            role="status"
            aria-label="Loading your images"
          >
            {[0, 1].map((key) => (
              <div key={key} className={ROW}>
                <SkeletonLine size="option" width="w-1/2" />
                <SkeletonLine size="small" width="w-2/3" />
                <SkeletonLine size="small" width="w-1/3" />
              </div>
            ))}
          </div>
        )}
      </Tile>
      {id && <Vulnerabilities id={id} />}
    </AccountFrame>
  );
}

/** One image: its reference, when it was checked, what the check found, and Remove (asked twice). */
function ImageItem({ image }: { image: Image }) {
  const remove = useRemoveImage();
  const [sure, setSure] = useState(false);
  return (
    <li className={ROW}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="text-option font-semibold break-all">{image.reference}</p>
          <p className="text-small text-text-muted first-letter:uppercase">
            <RelativeTime
              value={image.checkedAt ?? image.createdAt}
              prefix={image.checkedAt ? "checked" : "sent"}
            />
            {image.privateRegistry && " · private registry"}
          </p>
        </div>
        <Button
          variant="secondary"
          busy={remove.isPending}
          onClick={() => (sure ? remove.mutate(image.id) : setSure(true))}
        >
          {sure ? "Remove it" : "Remove"}
        </Button>
      </div>
      <ImageFindings image={image} vulnerabilitiesHref={`/account/images/?id=${image.id}`} />
      {remove.isError && (
        <p role="alert" className="text-small text-danger">
          {errorSentence(remove.error, "removing the image")}
        </p>
      )}
    </li>
  );
}

/** One image's known vulnerabilities, most severe first. */
function Vulnerabilities({ id }: { id: string }) {
  const list = useVulnerabilities(id, true);
  return (
    <Tile as="section" aria-labelledby="vulnerabilities-title" className="flex flex-col gap-2">
      <SectionTitle id="vulnerabilities-title">Known vulnerabilities</SectionTitle>
      {list.data ? (
        list.data.vulnerabilities.length === 0 ? (
          <EmptyState title="None known">Trivy found no known vulnerabilities in its packages.</EmptyState>
        ) : (
          <>
            <p className="text-small text-text-muted">
              {list.data.counts.critical} critical, {list.data.counts.high} high, {list.data.counts.medium}{" "}
              medium, {list.data.counts.low} low. Updating its packages fixes most.
            </p>
            <ul className="flex flex-col divide-y divide-divider text-small">
              {list.data.vulnerabilities.map((v) => (
                <li key={v.id + v.packageName} className="flex flex-col gap-0.5 py-2">
                  <span className="font-semibold">
                    {v.severity.toLowerCase()} · {v.id} · {v.packageName} {v.installed}
                  </span>
                  <span className="text-text-muted">
                    {v.title}
                    {v.fixed ? ` Fixed in ${v.fixed}.` : ""}
                  </span>
                </li>
              ))}
            </ul>
          </>
        )
      ) : list.isError ? (
        <ErrorState
          error={list.error}
          what="the list"
          onRetry={() => void list.refetch()}
          retrying={list.isFetching}
        />
      ) : (
        <div role="status" aria-label="Loading the list" className="flex flex-col gap-2">
          <SkeletonLine size="small" width="w-2/3" />
          <SkeletonLine size="small" width="w-1/2" />
        </div>
      )}
    </Tile>
  );
}
