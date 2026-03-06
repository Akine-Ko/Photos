#!/usr/bin/env python3
"""
Collect PerfMetric JSON events from adb logcat and export CSV reports.

Default outputs:
1) Summary by event latency: --output (default perf_metrics.csv)
2) Raw samples: <output>.raw.csv
3) Grouped latency by selected keys: <output>.groups.csv

Grouped report is useful for comparisons such as:
- used_hnsw=true vs false
- query_cache_hit=true vs false
- index_available=true vs false
"""

import argparse
import csv
import json
import math
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple


def parse_args():
    p = argparse.ArgumentParser(description="Parse PerfMetric logcat and export CSV reports.")
    p.add_argument("--session", help="Filter by session field in JSON.", default=None)
    p.add_argument("--device", help="adb device id.", default=None)
    p.add_argument("--timeout", type=int, help="Stop after N seconds.", default=None)
    p.add_argument("--output", help="Summary CSV path.", default="perf_metrics.csv")
    p.add_argument(
        "--raw-output",
        help="Raw sample CSV path. Default: <output>.raw.csv",
        default=None,
    )
    p.add_argument(
        "--group-output",
        help="Grouped CSV path. Default: <output>.groups.csv",
        default=None,
    )
    p.add_argument(
        "--group-keys",
        help="Comma-separated JSON keys for grouped stats.",
        default="used_hnsw,index_available,query_cache_hit,cache_hit,ran",
    )
    return p.parse_args()


def percentile(sorted_vals: List[float], p: float):
    if not sorted_vals:
        return None
    if len(sorted_vals) == 1:
        return float(sorted_vals[0])
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return float(sorted_vals[int(k)])
    d0 = sorted_vals[f] * (c - k)
    d1 = sorted_vals[c] * (k - f)
    return float(d0 + d1)


def parse_line(line: str):
    line = line.strip()
    pos = line.find("{")
    if pos == -1:
        return None
    snippet = line[pos:]
    try:
        data = json.loads(snippet)
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) else None


def scalar_cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, str)):
        return str(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def collect(args):
    cmd = ["adb"]
    if args.device:
        cmd += ["-s", args.device]
    cmd += ["logcat", "-v", "time", "PerfMetric:D", "*:S"]
    print("Running:", " ".join(cmd))
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )

    buckets: Dict[str, List[float]] = defaultdict(list)
    samples: List[Dict[str, Any]] = []
    start = time.time()
    try:
        for line in proc.stdout:
            if not line:
                continue
            data = parse_line(line)
            if not data:
                continue
            if args.session and data.get("session") != args.session:
                continue
            event = data.get("event")
            dur = data.get("dur_ms")
            if event is None or dur is None:
                continue
            try:
                dur = float(dur)
            except Exception:
                continue

            sample = {"event": str(event), "dur_ms": dur}
            for k, v in data.items():
                if k in ("event", "dur_ms"):
                    continue
                sample[str(k)] = v
            samples.append(sample)
            buckets[str(event)].append(dur)

            sys.stdout.write(f"\rCaptured {len(samples)} samples...")
            sys.stdout.flush()
            if args.timeout and (time.time() - start) >= args.timeout:
                break
    except KeyboardInterrupt:
        print("\nInterrupted, stopping capture...")
    finally:
        try:
            proc.terminate()
        except BaseException:
            pass
        try:
            proc.wait(timeout=2)
        except BaseException:
            try:
                proc.kill()
            except BaseException:
                pass
    print()
    return buckets, samples


def write_summary_csv(buckets: Dict[str, List[float]], output_path: Path):
    fields = ["event", "count", "mean_ms", "p50_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms"]
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(fields)
        for event, vals in sorted(buckets.items()):
            if not vals:
                continue
            vals_sorted = sorted(vals)
            mean = sum(vals_sorted) / len(vals_sorted)
            row = [
                event,
                len(vals_sorted),
                f"{mean:.2f}",
                f"{percentile(vals_sorted, 50):.2f}",
                f"{percentile(vals_sorted, 90):.2f}",
                f"{percentile(vals_sorted, 95):.2f}",
                f"{percentile(vals_sorted, 99):.2f}",
                f"{max(vals_sorted):.2f}",
            ]
            writer.writerow(row)
    return output_path


def write_raw_csv(samples: List[Dict[str, Any]], output_path: Path):
    extra_keys = set()
    for s in samples:
        for k in s.keys():
            if k in ("event", "dur_ms"):
                continue
            extra_keys.add(k)
    fields = ["event", "dur_ms"] + sorted(extra_keys)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(fields)
        for s in samples:
            row = [s.get("event", ""), s.get("dur_ms", "")]
            for key in fields[2:]:
                row.append(scalar_cell(s.get(key)))
            writer.writerow(row)
    return output_path


def build_group_rows(samples: List[Dict[str, Any]], group_keys: Iterable[str]):
    grouped: Dict[Tuple[str, str, str], List[float]] = defaultdict(list)
    clean_group_keys = [k for k in group_keys if k]
    for s in samples:
        event = str(s.get("event", ""))
        dur = s.get("dur_ms")
        if event == "" or not isinstance(dur, (int, float)):
            continue
        for key in clean_group_keys:
            if key not in s:
                continue
            value = s[key]
            if isinstance(value, (dict, list, tuple, set)):
                continue
            group_value = scalar_cell(value)
            grouped[(event, key, group_value)].append(float(dur))
    rows = []
    for (event, key, value), vals in sorted(grouped.items()):
        if not vals:
            continue
        vals_sorted = sorted(vals)
        mean = sum(vals_sorted) / len(vals_sorted)
        rows.append(
            [
                event,
                key,
                value,
                len(vals_sorted),
                f"{mean:.2f}",
                f"{percentile(vals_sorted, 50):.2f}",
                f"{percentile(vals_sorted, 90):.2f}",
                f"{percentile(vals_sorted, 95):.2f}",
                f"{percentile(vals_sorted, 99):.2f}",
                f"{max(vals_sorted):.2f}",
            ]
        )
    return rows


def write_group_csv(samples: List[Dict[str, Any]], group_keys: List[str], output_path: Path):
    fields = [
        "event",
        "group_key",
        "group_value",
        "count",
        "mean_ms",
        "p50_ms",
        "p90_ms",
        "p95_ms",
        "p99_ms",
        "max_ms",
    ]
    rows = build_group_rows(samples, group_keys)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(fields)
        writer.writerows(rows)
    return output_path


def with_suffix(path: Path, suffix: str):
    ext = path.suffix if path.suffix else ".csv"
    return path.with_name(path.stem + suffix + ext)


def main():
    args = parse_args()
    summary_out = Path(args.output)
    raw_out = Path(args.raw_output) if args.raw_output else with_suffix(summary_out, ".raw")
    group_out = Path(args.group_output) if args.group_output else with_suffix(summary_out, ".groups")
    group_keys = [k.strip() for k in args.group_keys.split(",") if k.strip()]
    try:
        buckets, samples = collect(args)
    except KeyboardInterrupt:
        print("\nInterrupted before collecting data; exiting.")
        sys.exit(1)
    if not buckets:
        print("No samples captured. Confirm app logs PerfMetric JSON payloads.")
        sys.exit(1)
    summary_path = write_summary_csv(buckets, summary_out)
    raw_path = write_raw_csv(samples, raw_out)
    group_path = write_group_csv(samples, group_keys, group_out)
    print(f"Wrote summary to {summary_path.resolve()}")
    print(f"Wrote raw samples to {raw_path.resolve()}")
    print(f"Wrote grouped stats to {group_path.resolve()}")


if __name__ == "__main__":
    main()
