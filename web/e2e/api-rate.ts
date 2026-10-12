/** The most timestamps (milliseconds) in any 60 seconds: a sliding window, which is never less than a fixed minute's. */
export function busiestMinute(times: number[]): number {
  let most = 0;
  for (let end = 0, start = 0; end < times.length; end++) {
    while (times[end]! - times[start]! >= 60_000) {
      start++;
    }
    most = Math.max(most, end - start + 1);
  }
  return most;
}
