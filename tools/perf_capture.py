#!/usr/bin/env python3
"""
收集设备 logcat 中 PerfMetric 日志，计算统计数据并输出为 CSV，便于用 Excel 查看。

使用约定：
- App 在测试按钮点击或关键步骤处输出：Log.i("PerfMetric", "{...json...}")
  JSON 至少包含：
    event: 事件名，如 "clip_encode" / "dino_encode" / "face_encode" / "text_search" 等
    dur_ms: 单次耗时（毫秒）
  可选：
    session: 本次测量的会话标识（便于过滤同一轮测试）
    extra 字段随意

示例日志：
  Log.i("PerfMetric", "{\"event\":\"clip_encode\",\"dur_ms\":42,\"session\":\"run1\"}");

运行脚本：
  python tools/perf_capture.py --session run1 --output perf_run1.csv

参数：
  --session   仅统计指定 session 的日志（可选，不传则不过滤）
  --device    指定设备序列号（adb -s，默认为空）
  --timeout   自动停止采集的秒数（可选，不传则需 Ctrl+C 结束）
  --output    输出 CSV 路径（默认 perf_metrics.csv）
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
import contextlib


def parse_args():
    p = argparse.ArgumentParser(description="Parse PerfMetric logcat to CSV")
    p.add_argument("--session", help="filter by session field in JSON", default=None)
    p.add_argument("--device", help="adb device id", default=None)
    p.add_argument("--timeout", type=int, help="stop after N seconds", default=None)
    p.add_argument("--output", help="output CSV path", default="perf_metrics.csv")
    return p.parse_args()


def percentile(sorted_vals, p):
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


def parse_line(line):
    """尝试从 logcat 行中提取 JSON，并返回 dict；失败则返回 None"""
    line = line.strip()
    pos = line.find("{")
    if pos == -1:
        return None
    snippet = line[pos:]
    try:
        return json.loads(snippet)
    except json.JSONDecodeError:
        return None


def collect(args):
    cmd = ["adb"]
    if args.device:
        cmd += ["-s", args.device]
    cmd += ["logcat", "-v", "time", "PerfMetric:D", "*:S"]
    print("Running:", " ".join(cmd))
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)

    buckets = defaultdict(list)
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
            buckets[event].append(dur)
            # 简短进度提示
            sys.stdout.write(f"\rCaptured {sum(len(v) for v in buckets.values())} samples...")
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
    return buckets


def write_csv(buckets, output_path):
    fields = ["event", "count", "mean_ms", "p50_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms"]
    output_path = Path(output_path)
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


def main():
    args = parse_args()
    try:
        buckets = collect(args)
    except KeyboardInterrupt:
        print("\nInterrupted before collecting data; exiting.")
        sys.exit(1)
    if not buckets:
        print("No samples captured. Confirm app is logging with tag PerfMetric and JSON payload.")
        sys.exit(1)
    out = write_csv(buckets, args.output)
    print(f"Wrote metrics to {out.resolve()}")


if __name__ == "__main__":
    main()
