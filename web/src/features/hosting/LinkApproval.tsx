import { useState } from "react";
import { Link, useLocation } from "wouter";
import { KeyValueList } from "@/components/ui/KeyValues";
import { PageTitle } from "@/components/ui/Headers";
import {
  useApproveHostLink,
  useDenyHostLink,
  useGpuPriceBand,
  useHostLink,
  type HostLink,
} from "@/api/hosting";
import { ApiError } from "@/api/errors";
import { FRAME, GRID } from "@/components/shell/Layout";
import { Banner } from "@/components/ui/Banner";
import { Button, buttonClasses } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Money } from "@/components/ui/Money";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { Tile, TileLabel } from "@/components/ui/Tile";
import { useAfterPaint } from "@/lib/after-first-screen";
import { cn } from "@/lib/cn";
import { useNow } from "@/lib/clock";
import { gigabytes, gpuName } from "@/lib/gpu";
import { formatCountdown, youKeep } from "@/lib/hosting";
import { problemMessage } from "@/lib/problems";
import { useTitle } from "@/lib/title";
import { ListingFields, useListingDraft } from "./PriceForm";
import { laptopPath } from "@/sites/host/paths";

const SYSTEMS: Record<string, string> = { windows: "Windows", linux: "Linux", macos: "macOS" };
const MAX_NAME = 100;

/**
 * Where "Link this laptop to your account?" is answered (ADR 0032): the page the
 * agent's link opens. It shows exactly which machine is asking, as the machine said it, with how long the link has left,
 * and one click, "Approve and start earning", saves the name, the price and the approval together. The price is the
 * middle of what other hosts with that GPU charge, and "Change" opens the same price and limits form a machine has.
 * "This isn't my laptop" denies it. An expired or denied link says so plainly. Approving makes the machine pending:
 * during the pilot, each new laptop is checked before it goes live.
 */
export function LinkApproval({ id }: { id: string }) {
  useTitle("Link this laptop");
  // The page is drawn whole first, bars where the values go; the link is asked for after that paint.
  const painted = useAfterPaint();
  const link = useHostLink(id, painted);

  const left = <Machine link={link.data} />;
  return (
    <div className={cn(FRAME, GRID, "items-start pt-2 pb-10 md:pt-4")}>
      <div className="col-span-6 xl:col-span-5">{left}</div>
      <div className="col-span-6 xl:col-span-7">
        {link.data ? (
          <Answer link={link.data} />
        ) : link.isError ? (
          <LinkError error={link.error} retry={() => void link.refetch()} retrying={link.isFetching} />
        ) : (
          <AnswerSkeleton />
        )}
      </div>
    </div>
  );
}

/**
 * The raised card: the laptop as it described itself, and how long the link has left. It is drawn whole while the link is
 * looked up, its title and labels in place and bars where the values go, with the rows of a laptop with one GPU, so the
 * tile is the height it will be and nothing below it moves when the values arrive.
 */
function Machine({ link }: { link: HostLink | undefined }) {
  const now = useNow("second");
  const left = link?.state === "pending" ? formatCountdown(link.expiresAt, now) : null;
  return (
    <Tile
      tone="raised"
      size="lg"
      as="section"
      aria-labelledby="link-title"
      aria-busy={link === undefined}
      className="flex flex-col gap-4"
    >
      <TileLabel>A laptop is asking to be linked</TileLabel>
      <PageTitle variant="panel" id="link-title">
        Link this laptop to your account?
      </PageTitle>
      <KeyValueList
        layout="split"
        size="body"
        divided
        items={[
          ["Laptop", shown(link?.machineName)],
          ...(link === undefined
            ? [["Graphics", shown(undefined)] as const]
            : link.gpus.map(
                (gpu) => ["Graphics", `${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}`] as const,
              )),
          ["System", shown(link === undefined ? undefined : (SYSTEMS[link.os] ?? link.os))],
          ["App version", shown(link?.agentVersion)],
        ]}
      />
      <p className="min-h-10 text-small text-ink-subtle" aria-live="off">
        {link === undefined
          ? ""
          : link.state === "pending"
            ? left
              ? `Expires in ${left}. Only approve it if you started this on your own laptop.`
              : "This link has just expired."
            : "Only approve a link you started on your own laptop."}
      </p>
    </Tile>
  );
}

/** A value, or its place held at its size while the link loads. */
function shown(value: string | undefined) {
  return value ?? <Skeleton className="skeleton block h-6 w-32" />;
}

function AnswerSkeleton() {
  return (
    <Tile size="lg" className="flex flex-col gap-4" role="status" aria-label="Loading this link">
      <Skeleton className="h-10 pointer-coarse:h-touch" />
      <Skeleton className="h-24" />
      <Skeleton className="h-10 pointer-coarse:h-touch" />
    </Tile>
  );
}

function LinkError({ error, retry, retrying }: { error: unknown; retry: () => void; retrying: boolean }) {
  const gone = error instanceof ApiError && error.status === 404;
  return gone ? (
    <Tile size="lg" className="flex flex-col gap-2">
      <h2 className="text-section">We can&apos;t find that link</h2>
      <p className="text-body text-ink-subtle">
        It may be for another account, or the address is wrong. Start again from the app on your laptop.
      </p>
    </Tile>
  ) : (
    <ErrorState error={error} what="this link" onRetry={retry} retrying={retrying} />
  );
}

/** What the person can do with the link, by how it stands. */
function Answer({ link }: { link: HostLink }) {
  switch (link.state) {
    case "approved":
      return <Done link={link} />;
    case "denied":
      return (
        <Tile size="lg" className="flex flex-col gap-2">
          <h2 className="text-section">Nothing was linked</h2>
          <p className="text-body text-ink-subtle">
            This link was denied. If that was a mistake, start again from the app on your laptop.
          </p>
        </Tile>
      );
    case "expired":
      return (
        <Tile size="lg" className="flex flex-col gap-2">
          <h2 className="text-section">This link has expired</h2>
          <p className="text-body text-ink-subtle">
            Links last 10 minutes. Start again from the app on your laptop.
          </p>
        </Tile>
      );
    default:
      return <Approve link={link} />;
  }
}

/** The form: the name, the price in one line, and the one button. */
function Approve({ link }: { link: HostLink }) {
  const gpu = link.gpus.at(0);
  const band = useGpuPriceBand(gpu);
  const draft = useListingDraft(undefined, band);
  const approve = useApproveHostLink(link.id);
  const deny = useDenyHostLink(link.id);
  const [name, setName] = useState(link.machineName);
  const [changing, setChanging] = useState(false);
  const nameError =
    name.trim() === ""
      ? "Give it a name."
      : name.length > MAX_NAME
        ? "Use at most 100 characters."
        : undefined;
  const { price } = draft;
  const earn = price === null ? null : youKeep(price);
  const ready = nameError === undefined && draft.valid && price !== null;

  return (
    <Tile size="lg" as="section" aria-labelledby="approve-title" className="flex flex-col gap-4">
      <h2 id="approve-title" className="text-section">
        Name it, check the price, and approve
      </h2>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (ready && draft.keepFree !== null) {
            approve.mutate({
              name: name.trim(),
              batchPricePaisePerMin: price,
              gpuTempCapC: draft.temp,
              keepFreeGb: draft.keepFree,
            });
          }
        }}
      >
        <Field
          label="Name"
          value={name}
          maxLength={MAX_NAME + 20}
          autoComplete="off"
          error={nameError}
          hint="Only you see this."
          onChange={(event) => {
            setName(event.target.value);
            approve.reset();
          }}
        />
        <div className="flex flex-col gap-2 rounded-control bg-surface-2 px-4 py-4">
          <p className="min-h-12 text-body md:min-h-6" role="status">
            {earn === null || price === null ? (
              <span className="block w-full">
                <SkeletonLine size="body" width="w-3/4" />
              </span>
            ) : (
              <>
                You&apos;ll earn{" "}
                <strong className="font-semibold tabular">
                  <Money paise={earn.keep} /> an hour
                </strong>{" "}
                <span className="text-ink-subtle">
                  (renters pay <Money paise={price * 60} />)
                </span>
              </>
            )}
          </p>
          <p className="text-small text-ink-subtle">
            The middle of what others with this GPU charge. You keep 80%; you can change it any time.{" "}
            <button
              type="button"
              className="font-semibold text-ink underline underline-offset-4"
              aria-expanded={changing}
              onClick={() => setChanging(!changing)}
            >
              {changing ? "Hide" : "Change"}
            </button>
          </p>
        </div>
        {changing && (
          <div className="flex flex-col gap-4">
            <ListingFields draft={draft} band={band} gpuModel={gpu?.model} onChange={() => approve.reset()} />
          </div>
        )}
        {approve.isError && <Banner tone="danger">{problemMessage(approve.error)}</Banner>}
        {deny.isError && <Banner tone="danger">{problemMessage(deny.error)}</Banner>}
        <div className="flex flex-col items-center gap-2">
          <Button
            type="submit"
            large
            block
            busy={approve.isPending}
            disabled={!ready || deny.isPending}
            why={deny.isPending ? "It's being declined." : "Give it a name and a price first."}
          >
            Approve and start earning
          </Button>
          <button
            type="button"
            className="min-h-touch text-small font-semibold text-ink-subtle underline underline-offset-4 hover:text-ink"
            disabled={approve.isPending || deny.isPending}
            onClick={() => deny.mutate()}
          >
            This isn&apos;t my laptop
          </button>
        </div>
      </form>
    </Tile>
  );
}

/** Approved. While new laptops are checked first, it says so. */
function Done({ link }: { link: HostLink }) {
  return (
    <Tile size="lg" as="section" aria-labelledby="linked-title" className="flex flex-col gap-4">
      <h2 id="linked-title" className="text-title">
        Linked
      </h2>
      <p className="max-w-prose text-body text-ink">
        {link.awaitsActivation
          ? "We check every new laptop before it goes live. It shows as live on its page as soon as it is, and nothing is rented from it until then."
          : "Your laptop is linked and starts earning once it's connected."}
      </p>
      <p className="text-small text-ink">You can close this tab; the laptop finishes linking by itself.</p>
      {link.hostId && (
        <Link href={laptopPath(link.hostId)} className={buttonClasses("primary", true, false)}>
          Open your laptop
        </Link>
      )}
    </Tile>
  );
}

/** The screen the router draws at `/link/<id>/`: the id is the address's second part. */
export function LinkApprovalScreen() {
  const [, id] = useLocation()[0].split("/").filter(Boolean);
  return id ? <LinkApproval id={id} /> : null;
}
