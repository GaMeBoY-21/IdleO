/** Times as renters read them: in their own time zone, Indian English, never seconds unless they matter. */

const dateTime = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" });
const time = new Intl.DateTimeFormat("en-IN", { timeStyle: "short" });

export const formatDateTime = (iso: string) => dateTime.format(new Date(iso));
export const formatTime = (value: string | number) => time.format(new Date(value));

/** "2 min 30 s", "1 h 05 min": how long between two moments. */
export function formatDuration(fromIso: string, to: number | string): string {
  const seconds = Math.max(0, Math.round((new Date(to).getTime() - new Date(fromIso).getTime()) / 1000));
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min ${String(seconds % 60).padStart(2, "0")} s`;
  }
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")} min`;
}
