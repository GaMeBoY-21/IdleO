"""Log GPU temperature, power, utilisation, memory and SM clock once a second.

Standard library only. Needs nvidia-smi on PATH (Windows, or Linux/WSL).

    python gpu_logger.py --out results/qlora-gpu.csv       log until Ctrl+C
    python gpu_logger.py --out game.csv --minutes 60        log for an hour
    python gpu_logger.py --summary results/qlora-gpu.csv    min / mean / max
"""

import argparse
import csv
import datetime as dt
import os
import statistics
import subprocess
import sys
import time

QUERY = ["temperature.gpu", "power.draw", "utilization.gpu", "memory.used", "clocks.sm"]
COLUMNS = ["timestamp_utc", "temp_c", "power_w", "util_pct", "mem_used_mb", "sm_clock_mhz"]
HOT_C = 85


def read_gpu(gpu_index):
    out = subprocess.run(
        ["nvidia-smi", "-i", str(gpu_index),
         "--query-gpu=" + ",".join(QUERY), "--format=csv,noheader,nounits"],
        capture_output=True, text=True, timeout=10, check=True,
    ).stdout.strip().splitlines()[0]
    values = [v.strip() for v in out.split(",")]
    if len(values) != len(QUERY):
        raise ValueError(f"unexpected nvidia-smi output: {out!r}")
    # Laptops often report "[N/A]" for power; keep the cell empty.
    return ["" if v.startswith("[") or v == "N/A" else v for v in values]


def log(path, gpu_index, interval, minutes):
    new_file = not os.path.exists(path) or os.path.getsize(path) == 0
    deadline = time.monotonic() + minutes * 60 if minutes else None
    samples = 0
    with open(path, "a", newline="") as f:
        writer = csv.writer(f)
        if new_file:
            writer.writerow(COLUMNS)
        print(f"Logging GPU {gpu_index} every {interval:g}s to {path}. Ctrl+C to stop.")
        next_tick = time.monotonic()
        try:
            while deadline is None or time.monotonic() < deadline:
                stamp = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
                try:
                    writer.writerow([stamp] + read_gpu(gpu_index))
                    f.flush()  # a killed logger keeps everything up to the last second
                    samples += 1
                except (subprocess.SubprocessError, OSError, ValueError, IndexError) as e:
                    print(f"{stamp} skipped a sample: {e}", file=sys.stderr)
                next_tick += interval
                time.sleep(max(0.0, next_tick - time.monotonic()))
        except KeyboardInterrupt:
            pass
    print(f"Stopped after {samples} samples.")


def summarize(path):
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit(f"{path} has no samples")
    stamps = [dt.datetime.fromisoformat(r["timestamp_utc"]) for r in rows]
    gaps = [(b - a).total_seconds() for a, b in zip(stamps, stamps[1:])]
    interval = statistics.median(gaps) if gaps else 0.0

    print(f"{path}: {len(rows)} samples, {stamps[0].isoformat()} to {stamps[-1].isoformat()}, "
          f"interval {interval:g}s")
    print(f"{'column':<14}{'min':>10}{'mean':>10}{'max':>10}{'samples':>9}")
    for col in COLUMNS[1:]:
        vals = [float(r[col]) for r in rows if r.get(col, "").strip()]
        if vals:
            print(f"{col:<14}{min(vals):>10.1f}{statistics.fmean(vals):>10.1f}{max(vals):>10.1f}{len(vals):>9}")
        else:
            print(f"{col:<14}{'n/a':>10}{'n/a':>10}{'n/a':>10}{0:>9}")
    hot = sum(1 for r in rows if r["temp_c"].strip() and float(r["temp_c"]) >= HOT_C)
    print(f"minutes at or above {HOT_C} C: {hot * interval / 60:.1f} ({hot} samples)")


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--out", help="CSV file to append samples to")
    p.add_argument("--summary", metavar="CSV", help="print min, mean and max per column")
    p.add_argument("--gpu", type=int, default=0, help="GPU index (default 0)")
    p.add_argument("--interval", type=float, default=1.0, help="seconds between samples (default 1)")
    p.add_argument("--minutes", type=float, help="stop after this many minutes")
    args = p.parse_args()
    if args.summary:
        summarize(args.summary)
    elif args.out:
        log(args.out, args.gpu, args.interval, args.minutes)
    else:
        p.error("give --out to log, or --summary to summarise")


if __name__ == "__main__":
    main()
