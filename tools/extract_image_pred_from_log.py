#!/usr/bin/env python3
"""
Extract image-search TopK prediction rows from logcat text.

Usage:
  python tools/extract_image_pred_from_log.py ^
    --log reports/img_eval.log ^
    --gt gt/image_eval_gt_simple.csv ^
    --out gt/image_eval_pred_simple.csv ^
    --k 3

Expected log capture:
  adb logcat -c
  adb logcat -v time ImageSearchEngine:I PerfMetric:D *:S | Out-File reports\\img_eval.log -Encoding utf8

The script groups one search run by seeing:
  - ImageSearchEngine "top[i] <key> score=..."
  - and finalization on PerfMetric line containing "image_search_total"
Fallback grouping: when a new top[0] appears after existing tops.
"""

from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path
from typing import Dict, List


TOP_RE = re.compile(r"top\[(\d+)\]\s+(\S+)\s+score=")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Extract image search predictions from log.")
    p.add_argument("--log", required=True, help="Captured log file path.")
    p.add_argument("--gt", required=True, help="GT file to get query_id order.")
    p.add_argument("--out", required=True, help="Output prediction CSV path.")
    p.add_argument("--k", type=int, default=3, help="Top-K count to output.")
    p.add_argument(
        "--key-mode",
        default="id",
        choices=["id", "raw"],
        help="Output key style: id (last path segment) or raw (as logged).",
    )
    return p.parse_args()


def read_text_auto(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe"):
        return raw.decode("utf-16-le", errors="ignore")
    if raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16-be", errors="ignore")
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig", errors="ignore")
    if raw and (raw.count(0) / len(raw)) > 0.2:
        return raw.decode("utf-16-le", errors="ignore")
    return raw.decode("utf-8", errors="ignore")


def split_keys(raw: str) -> List[str]:
    text = (raw or "").strip().strip('"').strip("'")
    if not text:
        return []
    text = text.replace("；", ";").replace("，", ";")
    return [x.strip() for x in text.split(";") if x.strip()]


def load_gt_query_ids(path: Path) -> List[str]:
    out: List[str] = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        for r in rd:
            qid = (r.get("query_id") or "").strip()
            if qid:
                out.append(qid)
    if not out:
        raise RuntimeError(f"No query_id found in GT: {path}")
    return out


def normalize_key(raw_key: str, mode: str) -> str:
    key = raw_key.strip()
    if mode == "raw":
        return key
    # id mode: content://.../123 -> 123
    if "/" in key:
        key = key.rsplit("/", 1)[-1]
    if "#f" in key:
        key = key.split("#f", 1)[0]
    return key


def extract_groups(text: str) -> List[List[str]]:
    groups: List[List[str]] = []
    cur: Dict[int, str] = {}

    def flush():
        nonlocal cur
        if not cur:
            return
        mx = max(cur.keys())
        arr = []
        for i in range(mx + 1):
            if i in cur:
                arr.append(cur[i])
        if arr:
            groups.append(arr)
        cur = {}

    for line in text.splitlines():
        if "ImageSearchEngine" in line:
            m = TOP_RE.search(line)
            if m:
                idx = int(m.group(1))
                key = m.group(2)
                # fallback boundary: a new top[0] means new query.
                if idx == 0 and cur:
                    flush()
                cur[idx] = key
                continue
        if "PerfMetric" in line and "image_search_total" in line:
            flush()

    flush()
    return groups


def main() -> int:
    args = parse_args()
    log_path = Path(args.log)
    gt_path = Path(args.gt)
    out_path = Path(args.out)
    k = max(1, int(args.k))

    if not log_path.exists():
        raise FileNotFoundError(f"log file not found: {log_path}")
    if not gt_path.exists():
        raise FileNotFoundError(f"gt file not found: {gt_path}")

    query_ids = load_gt_query_ids(gt_path)
    text = read_text_auto(log_path)
    groups_raw = extract_groups(text)

    # normalize + trim K
    groups: List[List[str]] = []
    for g in groups_raw:
        norm = [normalize_key(x, args.key_mode) for x in g]
        groups.append(norm[:k])

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        wr = csv.DictWriter(f, fieldnames=["query_id", "topk_keys"])
        wr.writeheader()
        for i, qid in enumerate(query_ids):
            vals = groups[i] if i < len(groups) else []
            wr.writerow({"query_id": qid, "topk_keys": ";".join(vals)})

    print(f"Wrote pred file: {out_path}")
    print(f"gt_queries={len(query_ids)}")
    print(f"log_groups={len(groups)}")
    if len(groups) != len(query_ids):
        print("WARNING: group count != query count. Check query order / capture log.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

