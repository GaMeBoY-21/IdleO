/**
 * Times as renters read them (web/DESIGN.md "Times"): relative where it's about how long ago ("started 18 min ago"),
 * with the exact time, in their own time zone and Indian English, a hover away; never seconds unless they matter.
 */

const dateTime = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" });
const time = new Intl.DateTimeFormat("en-IN", { timeStyle: "short" });
const dayMonth = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short" });

const dayKey = new Intl.DateTimeFormat("en-CA", { year: "numeric", month: "2-digit", day: "2-digit" });

/** The calendar day a moment falls on, in the reader's own time zone ("2026-10-07"): what a day's group is keyed by. */
export const dayOf = (value: string | number) => dayKey.format(new Date(value));

/** A day's heading: "Today", "Yesterday", then the date ("12 Oct"). */
export function dayHeading(value: string, now: number): string {
  const day = dayOf(value);
  if (day === dayOf(now)) {
    return "Today";
  }
  if (day === dayOf(now - 24 * 60 * 60_000)) {
    return "Yesterday";
  }
  return dayMonth.format(new Date(value));
}

export const formatDateTime = (iso: string) => dateTime.format(new Date(iso));
export const formatTime = (value: string | number) => time.format(new Date(value));

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * "just now", "18 min ago", "2 h ago", "yesterday", "3 days ago", then the date; "in 12 min" for a time to come.
 * Whole units, rounded down, so a time never reads as later than it is.
 */
export function formatRelative(iso: string, now: number): string {
  const diff = now - new Date(iso).getTime();
  const ahead = diff < 0;
  const span = Math.abs(diff);
  if (span < MINUTE) {
    return ahead ? "in under a minute" : "just now";
  }
  const words =
    span < HOUR ? `${Math.floor(span / MINUTE)} min` : span < DAY ? `${Math.floor(span / HOUR)} h` : null;
  if (words !== null) {
    return ahead ? `in ${words}` : `${words} ago`;
  }
  if (!ahead && span < 2 * DAY) {
    return "yesterday";
  }
  if (!ahead && span < 7 * DAY) {
    return `${Math.floor(span / DAY)} days ago`;
  }
  return dayMonth.format(new Date(iso));
}

/** "2 min 30 s", "41 min", "1 h 05 min": how long between two moments; seconds only while they matter. */
export function formatDuration(fromIso: string, to: number | string): string {
  const seconds = Math.max(0, Math.round((new Date(to).getTime() - new Date(fromIso).getTime()) / 1000));
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 10) {
    return `${minutes} min ${String(seconds % 60).padStart(2, "0")} s`;
  }
  if (minutes < 60) {
    return `${minutes} min`;
  }
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")} min`;
}

/** A running clock, "18:04" or "1:02:05": whole seconds since `fromIso`, never negative. */
export function formatElapsed(fromIso: string, to: number | string): string {
  const total = Math.max(0, Math.floor((new Date(to).getTime() - new Date(fromIso).getTime()) / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = String(total % 60).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${seconds}` : `${minutes}:${seconds}`;
}
