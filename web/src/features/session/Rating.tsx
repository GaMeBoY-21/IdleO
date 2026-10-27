import { Star } from "lucide-react";
import { useState } from "react";
import { ApiError } from "@/api/errors";
import { isRecentEnd, useRate, useRating, type SessionRating } from "@/api/trust";
import { Button } from "@/components/ui/Button";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { useNow } from "@/lib/clock";
import { problemMessage } from "@/lib/problems";
import { formatDateTime } from "@/lib/time";

const STARS = [1, 2, 3, 4, 5] as const;
const COMMENT_MAX = 500;

/**
 * "How did it go?" after a completed session (ADR 0028): five stars, and one tap is enough: it rates at once. A comment
 * can follow, then or later, and the rating can be changed until 7 days after it was given. The block is always the
 * same height (a title, the stars, with "Add a comment" beside them, and one line under them), so what arrives moves
 * nothing. A rating that has closed shows its stars only. Only we read the comment:
 * the host never sees it, or who rated.
 */
export function Rating({
  sessionId,
  endedAt,
  enabled,
}: {
  sessionId: string;
  endedAt: string | null;
  enabled: boolean;
}) {
  const now = useNow("half-minute");
  const rating = useRating(sessionId, enabled, endedAt);
  const rate = useRate(sessionId);
  const [draft, setDraft] = useState<string | null>(null);
  // The control plane notes a session's end a moment after the session changes; until it has, it says not-found.
  const unrecorded = rating.isError && rating.error instanceof ApiError && rating.error.status === 404;
  const current = rating.data;
  const known = current !== undefined;
  const closed = known && !current.rateable;
  const stars = current?.stars ?? null;
  const hasRating = stars !== null;

  // The comment's own box: opened by the tap that rates (or "Add a comment"), prefilled with the saved one.
  const commentOpen = draft !== null;
  const saved = current?.comment ?? "";

  // One tap rates, keeping the comment as it was saved; the comment's box opens once the rating is saved.
  const pick = (value: number) =>
    rate.mutate(
      { stars: value, comment: saved },
      { onSuccess: (done) => setDraft((open) => open ?? done.comment ?? "") },
    );

  return (
    <Tile as="section" aria-labelledby="rating-title" className="flex flex-col gap-1">
      <h2 id="rating-title" className="text-section">
        {hasRating
          ? closed
            ? "You rated this session"
            : "Thanks for rating"
          : closed
            ? "Rating closed"
            : "How did it go?"}
      </h2>
      <div className="flex items-center justify-between gap-2">
        <StarRow
          value={stars}
          disabled={!known || closed || rate.isPending}
          onPick={pick}
          label={hasRating ? `You gave ${stars} out of 5 stars` : "Rate this session out of 5 stars"}
        />
        {/* In the stars' own row, so it arriving adds no height. */}
        {known && !closed && hasRating && !commentOpen && (
          <Button variant="quiet" onClick={() => setDraft(saved)}>
            {saved === "" ? "Add a comment" : "Edit your comment"}
          </Button>
        )}
      </div>
      <Caption
        current={current}
        unrecorded={unrecorded}
        recent={isRecentEnd(endedAt, now)}
        loading={!known && !rating.isError}
        failed={rating.isError && !unrecorded}
        retry={() => void rating.refetch()}
        retrying={rating.isFetching}
        error={rate.isError ? problemMessage(rate.error) : null}
      />
      {known && !closed && hasRating && commentOpen && (
        <CommentBox
          value={draft}
          onChange={setDraft}
          unchanged={draft.trim() === saved}
          saving={rate.isPending}
          onSave={() => rate.mutate({ stars, comment: draft.trim() })}
        />
      )}
    </Tile>
  );
}

/** Five stars of at least 44 px each: one tap rates. Filled up to the rating; never colour alone, since each says its number. */
function StarRow({
  value,
  disabled,
  onPick,
  label,
}: {
  value: number | null;
  disabled: boolean;
  onPick: (value: number) => void;
  label: string;
}) {
  return (
    <div role="group" aria-label={label} className="-ml-2.5 flex">
      {STARS.map((star) => {
        const filled = value !== null && star <= value;
        return (
          <button
            key={star}
            type="button"
            disabled={disabled}
            aria-label={`${star} ${star === 1 ? "star" : "stars"}`}
            aria-pressed={value === star}
            onClick={() => onPick(star)}
            className="flex size-touch items-center justify-center rounded-control hover:bg-canvas disabled:cursor-default disabled:hover:bg-transparent"
          >
            <Star
              aria-hidden="true"
              className={cn("size-7", filled ? "text-text" : "text-control")}
              fill={filled ? "currentColor" : "none"}
              strokeWidth={1.75}
            />
          </button>
        );
      })}
    </div>
  );
}

/** The one line under the stars: what happens next, until when it can change, or why it can't. */
function Caption({
  current,
  unrecorded,
  recent,
  loading,
  failed,
  retry,
  retrying,
  error,
}: {
  current: SessionRating | undefined;
  unrecorded: boolean;
  recent: boolean;
  loading: boolean;
  failed: boolean;
  retry: () => void;
  retrying: boolean;
  error: string | null;
}) {
  if (loading) {
    return <SkeletonLine size="caption" width="w-1/2" />;
  }
  if (error !== null) {
    return (
      <p role="alert" className="min-h-4.5 text-caption text-danger">
        {error}
      </p>
    );
  }
  if (failed) {
    return (
      <p className="flex min-h-4.5 items-center gap-2 text-caption text-text-2">
        We couldn&apos;t load your rating.
        <Button variant="quiet" busy={retrying} onClick={retry}>
          Try again
        </Button>
      </p>
    );
  }
  let text: string;
  if (unrecorded || current === undefined) {
    text = recent ? "One moment: we're noting that it ended." : "This session can't be rated.";
  } else if (!current.rateable) {
    text =
      current.stars == null
        ? "Ratings close 7 days after a session ends."
        : `It closed on ${current.rateableUntil ? formatDateTime(current.rateableUntil) : "its last day"}.`;
  } else if (current.stars == null) {
    text = "Optional. It helps us look after hosts.";
  } else {
    text = `You can change it until ${current.rateableUntil ? formatDateTime(current.rateableUntil) : "7 days from now"}.`;
  }
  return <p className="min-h-4.5 text-caption text-text-2">{text}</p>;
}

function CommentBox({
  value,
  onChange,
  unchanged,
  saving,
  onSave,
}: {
  value: string;
  onChange: (value: string) => void;
  unchanged: boolean;
  saving: boolean;
  onSave: () => void;
}) {
  return (
    <form
      className="flex flex-col gap-2 pt-1"
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}
    >
      <label htmlFor="rating-comment" className="text-small font-medium">
        Comment (optional)
      </label>
      <textarea
        id="rating-comment"
        value={value}
        rows={3}
        maxLength={COMMENT_MAX}
        onChange={(event) => onChange(event.target.value)}
        className="rounded-control bg-canvas px-4 py-3 text-body text-text"
        aria-describedby="rating-comment-help"
      />
      <p id="rating-comment-help" className="text-caption text-text-2">
        Only we read it. The host never sees it, or who you are.{" "}
        <span className="tabular">{value.length}</span>/{COMMENT_MAX}
      </p>
      <Button type="submit" variant="ghost" className="self-start" busy={saving} disabled={unchanged}>
        Save comment
      </Button>
    </form>
  );
}
