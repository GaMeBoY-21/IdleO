import { ChevronLeft, ChevronRight } from "lucide-react";
import { Link } from "wouter";
import {
  useDownloadExport,
  useExports,
  useRequestExport,
  useYourData,
  type DataExport,
  type YourData as Data,
} from "@/api/privacy";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Button } from "@/components/ui/Button";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { useAfterPaint } from "@/lib/after-first-screen";
import { formatBytes } from "@/lib/bytes";
import { problemMessage } from "@/lib/problems";
import { formatDay } from "@/lib/time";
import { useTitle } from "@/lib/title";

/** The groups, as the server names them, in the person's words; the server sends them in this order. */
export const GROUP_TITLES: Record<string, string> = {
  account: "Your account",
  sessions: "Sessions",
  files: "Files",
  money: "Money",
  hosting: "Hosting",
  messages: "Messages",
  reports: "Reports and ratings",
};

const ROW = "flex min-h-18 flex-col justify-center gap-0.5 py-3";

/**
 * Account → Your data (ADR 0037): everything we hold about the person, by kind, with how much and since when; the
 * whole of it as one file, made in the background and emailed when ready; and the way to delete the account. Each row
 * is two lines, loading or loaded, so nothing moves when the figures arrive.
 */
export function YourData() {
  useTitle("Your data");
  const painted = useAfterPaint();
  const held = useYourData(painted);
  const exports = useExports(painted);
  const since = staleSince(held, exports);

  return (
    <AccountFrame title="Your data" className="xl:grid xl:grid-cols-5 xl:items-start">
      <Link
        href="/account/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-muted underline md:hidden"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Account
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <Tile
        as="section"
        aria-labelledby="held-title"
        className="flex flex-col gap-1 md:p-6 xl:col-span-3 xl:p-7"
      >
        <SectionTitle id="held-title">What we hold</SectionTitle>
        <p className="mb-2 max-w-prose text-small text-text-muted">
          Everything we keep about you, by kind. To correct your name, edit it in Account; to change your
          email, contact us.
        </p>
        {held.data ? (
          <Groups data={held.data} />
        ) : held.isError ? (
          <ErrorState
            error={held.error}
            what="what we hold"
            onRetry={() => void held.refetch()}
            retrying={held.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-divider"
            role="status"
            aria-label="Loading what we hold"
          >
            {Object.keys(GROUP_TITLES).map((group) => (
              <div key={group} className={ROW}>
                <SkeletonLine size="option" width="w-1/3" />
                <SkeletonLine size="small" width="w-2/3" />
              </div>
            ))}
          </div>
        )}
      </Tile>
      <div className="flex flex-col gap-4 xl:col-span-2">
        <Tile as="section" aria-labelledby="download-title" className="flex flex-col gap-3 md:p-6 xl:p-7">
          <SectionTitle id="download-title">Download my data</SectionTitle>
          <p className="max-w-prose text-small text-text-muted">
            One file with all of it, and links to your files that work for 7 days. We make it in the
            background and email you when it&apos;s ready. You can ask once a day.
          </p>
          <ExportBox exports={exports} />
        </Tile>
        <Tile as="section" aria-labelledby="delete-title" className="flex flex-col gap-1 md:p-6 xl:p-7">
          <SectionTitle id="delete-title">Delete my account</SectionTitle>
          <Link href="/account/delete/" className="flex min-h-touch items-center gap-3 hover:bg-ground">
            <p className="flex-1 text-small text-text-muted">
              What&apos;s erased, what we must keep, and the 7 days you have to change your mind.
            </p>
            <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-text-muted" strokeWidth={1.75} />
          </Link>
        </Tile>
      </div>
    </AccountFrame>
  );
}

function Groups({ data }: { data: Data }) {
  return (
    <ul className="flex flex-col divide-y divide-divider">
      {data.groups.map((group) => {
        const items = group.items.filter((item) => item.count > 0);
        return (
          <li key={group.group} className={ROW}>
            <p className="text-option font-semibold">{GROUP_TITLES[group.group] ?? group.group}</p>
            <p className="truncate text-small text-text-muted">
              {items.length === 0 ? "Nothing" : items.map((item) => describe(item)).join(" · ")}
            </p>
          </li>
        );
      })}
    </ul>
  );
}

/** "3 sessions, 2 Oct 2026 to 20 Oct 2026"; one thing, since its date. */
export function describe(item: Data["groups"][number]["items"][number]): string {
  const counted = `${item.count} ${item.label}`;
  if (!item.earliest || !item.latest) {
    return counted;
  }
  const from = formatDay(item.earliest);
  const to = formatDay(item.latest);
  return from === to ? `${counted}, ${from}` : `${counted}, ${from} to ${to}`;
}

function ExportBox({ exports }: { exports: ReturnType<typeof useExports> }) {
  const request = useRequestExport();
  const download = useDownloadExport();
  const latest: DataExport | undefined = exports.data?.exports[0];

  const save = (id: string) =>
    download.mutate(id, {
      onSuccess: (link) => {
        window.location.assign(link.url);
        download.reset();
      },
    });

  if (exports.isError && !exports.data) {
    return (
      <ErrorState
        error={exports.error}
        what="your exports"
        onRetry={() => void exports.refetch()}
        retrying={exports.isFetching}
      />
    );
  }
  const status = !exports.data
    ? null
    : latest?.state === "requested"
      ? "Being made. We'll email you when it's ready."
      : latest?.state === "ready" && latest.expiresAt
        ? `Ready${latest.sizeBytes ? `, ${formatBytes(latest.sizeBytes)}` : ""}. Kept until ${formatDay(latest.expiresAt)}.`
        : latest?.state === "failed"
          ? "We couldn't make your last one. Ask again."
          : "Nothing made yet.";
  const error = request.isError
    ? problemMessage(request.error)
    : download.isError
      ? problemMessage(download.error)
      : undefined;
  return (
    <div className="flex flex-col gap-3">
      <p className="min-h-6 text-small" aria-live="polite">
        {status ?? <SkeletonLine size="small" width="w-2/3" />}
      </p>
      <div className="flex flex-wrap gap-3">
        {latest?.state === "ready" ? (
          <Button busy={download.isPending} onClick={() => save(latest.id)}>
            Download
          </Button>
        ) : (
          <Button
            disabled={!exports.data || latest?.state === "requested"}
            busy={request.isPending}
            onClick={() => request.mutate()}
          >
            Make my file
          </Button>
        )}
      </div>
      <p role="alert" className="min-h-4.5 text-caption text-danger">
        {error}
      </p>
    </div>
  );
}
