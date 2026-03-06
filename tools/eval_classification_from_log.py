#!/usr/bin/env python3
"""
Evaluate classification quality from ClassificationWorker logcat output.

Inputs:
  1) GT CSV (e.g. gt/classification_gt_500.csv)
  2) Log file captured with:
       adb logcat -c
       adb logcat -v time ClassificationWorker:I *:S > reports/cls_eval.log

Output:
  - per-row comparison CSV
  - text summary
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import Counter
from pathlib import Path
from typing import Dict, Any


SCORE_RE = re.compile(
    r"Score for .*?\((content://[^)]+)\) -> ([A-Za-z_]+) = "
    r"([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?) "
    r"\(threshold ([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\)"
)
NO_LABEL_RE = re.compile(r"No label scored for .*?\((content://[^)]+)\)")
NO_EMB_RE = re.compile(r"No cached embedding for (content://\S+)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Evaluate classification from logcat.")
    p.add_argument("--gt", default="gt/classification_gt_500.csv", help="GT CSV path.")
    p.add_argument("--log", required=True, help="Classification log path.")
    p.add_argument(
        "--out",
        default="reports/classification_eval_rows.csv",
        help="Per-row output CSV path.",
    )
    p.add_argument(
        "--summary-out",
        default="reports/classification_eval_summary.txt",
        help="Summary output text path.",
    )
    return p.parse_args()


def norm_label(label: str) -> str:
    v = (label or "").strip().strip('"').upper()
    if v == "IDPHOTO":
        return "CARD"
    if not v:
        return "UNKNOWN"
    return v


def read_text_auto(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe"):
        return raw.decode("utf-16-le", errors="ignore")
    if raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16-be", errors="ignore")
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig", errors="ignore")
    # Fallback: if it contains many NUL bytes, treat as UTF-16-LE.
    if raw and (raw.count(0) / len(raw)) > 0.2:
        return raw.decode("utf-16-le", errors="ignore")
    return raw.decode("utf-8", errors="ignore")


def load_events(log_path: Path) -> Dict[str, Dict[str, Any]]:
    events: Dict[str, Dict[str, Any]] = {}
    text = read_text_auto(log_path)
    for line in text.splitlines():
        if "ClassificationWorker" not in line:
            continue

        m = SCORE_RE.search(line)
        if m:
            uri = m.group(1)
            raw_label = norm_label(m.group(2))
            score = float(m.group(3))
            threshold = float(m.group(4))
            passed = score >= threshold
            pred = raw_label if passed else "UNKNOWN"
            events[uri] = {
                "pred": pred,
                "source": "score",
                "raw_label": raw_label,
                "score": score,
                "threshold": threshold,
                "score_pass": passed,
            }
            continue

        m = NO_LABEL_RE.search(line)
        if m:
            uri = m.group(1)
            events[uri] = {
                "pred": "UNKNOWN",
                "source": "no_label",
                "raw_label": "",
                "score": "",
                "threshold": "",
                "score_pass": "",
            }
            continue

        m = NO_EMB_RE.search(line)
        if m:
            uri = m.group(1)
            # Keep score/no_label event if already present.
            if uri not in events:
                events[uri] = {
                    "pred": "UNKNOWN",
                    "source": "no_embedding",
                    "raw_label": "",
                    "score": "",
                    "threshold": "",
                    "score_pass": "",
                }

    return events


def main() -> int:
    args = parse_args()

    gt_path = Path(args.gt)
    log_path = Path(args.log)
    out_path = Path(args.out)
    summary_path = Path(args.summary_out)

    if not gt_path.exists():
        raise FileNotFoundError(f"GT CSV not found: {gt_path}")
    if not log_path.exists():
        raise FileNotFoundError(f"log file not found: {log_path}")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.parent.mkdir(parents=True, exist_ok=True)

    events = load_events(log_path)

    rows = []
    with gt_path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for r in reader:
            uri = (r.get("mediaKey") or r.get("contentUri") or "").strip()
            gt = norm_label(r.get("gt_category", ""))
            ev = events.get(uri)
            if ev is None:
                pred = "MISSING"
                source = "missing_in_log"
                raw_label = ""
                score = ""
                threshold = ""
                score_pass = ""
            else:
                pred = ev["pred"]
                source = ev["source"]
                raw_label = ev["raw_label"]
                score = ev["score"]
                threshold = ev["threshold"]
                score_pass = ev["score_pass"]

            rows.append(
                {
                    "id": (r.get("id") or "").strip(),
                    "displayName": (r.get("displayName") or "").strip(),
                    "bucketName": (r.get("bucketName") or "").strip(),
                    "mediaKey": uri,
                    "gt_category": gt,
                    "pred_category": pred,
                    "correct": str(gt == pred).lower(),
                    "event_source": source,
                    "raw_label": raw_label,
                    "score": score,
                    "threshold": threshold,
                    "score_pass": score_pass,
                }
            )

    # Summary metrics
    total = len(rows)
    missing = sum(1 for r in rows if r["pred_category"] == "MISSING")
    overall_correct = sum(1 for r in rows if r["gt_category"] == r["pred_category"])
    overall_acc = (overall_correct / total) if total else 0.0

    known = [r for r in rows if r["gt_category"] != "UNKNOWN"]
    known_total = len(known)
    known_correct = sum(1 for r in known if r["gt_category"] == r["pred_category"])
    known_acc = (known_correct / known_total) if known_total else 0.0
    known_coverage = (
        sum(1 for r in known if r["pred_category"] not in ("UNKNOWN", "MISSING")) / known_total
        if known_total
        else 0.0
    )

    label_gt = Counter(r["gt_category"] for r in rows)
    label_pred = Counter(r["pred_category"] for r in rows)
    conf = Counter(
        (r["gt_category"], r["pred_category"])
        for r in rows
        if r["gt_category"] != r["pred_category"]
    )

    # Write detailed rows
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=[
                "id",
                "displayName",
                "bucketName",
                "mediaKey",
                "gt_category",
                "pred_category",
                "correct",
                "event_source",
                "raw_label",
                "score",
                "threshold",
                "score_pass",
            ],
        )
        writer.writeheader()
        writer.writerows(rows)

    lines = [
        f"gt_file={gt_path}",
        f"log_file={log_path}",
        f"rows_file={out_path}",
        "",
        f"total={total}",
        f"missing_in_log={missing}",
        f"overall_correct={overall_correct}",
        f"overall_accuracy={overall_acc:.4f}",
        f"known_total(exclude_UNKNOWN_gt)={known_total}",
        f"known_correct={known_correct}",
        f"known_accuracy={known_acc:.4f}",
        f"known_coverage(pred_not_UNKNOWN_or_MISSING)={known_coverage:.4f}",
        "",
        "gt_distribution:",
    ]
    for k, v in sorted(label_gt.items(), key=lambda x: (-x[1], x[0])):
        lines.append(f"  {k}: {v}")
    lines.append("")
    lines.append("pred_distribution:")
    for k, v in sorted(label_pred.items(), key=lambda x: (-x[1], x[0])):
        lines.append(f"  {k}: {v}")
    lines.append("")
    lines.append("top_mismatches(gt->pred):")
    for (g, p), c in conf.most_common(20):
        lines.append(f"  {g} -> {p}: {c}")

    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"Wrote rows: {out_path}")
    print(f"Wrote summary: {summary_path}")
    print(f"overall_accuracy={overall_acc:.4f}")
    print(f"known_accuracy={known_acc:.4f}")
    print(f"known_coverage={known_coverage:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
