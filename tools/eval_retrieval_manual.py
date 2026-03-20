#!/usr/bin/env python3
"""
Evaluate retrieval quality from manually prepared GT and prediction CSV files.

CSV format:
  GT (--gt):
    query_id,query_key,relevant_keys
    q1,content://.../1000001,"content://.../1002;content://.../1003"

  Prediction (--pred):
    query_id,topk_keys
    q1,"content://.../1003;content://.../1008;content://.../1002"

Notes:
  - key strings can be contentUri, numeric ids, filenames, etc. as long as GT and pred match.
  - multiple keys are separated by ';'
"""

from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path
from typing import Dict, List


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Evaluate retrieval metrics from CSV.")
    p.add_argument("--gt", required=True, help="GT CSV path.")
    p.add_argument("--pred", required=True, help="Prediction CSV path.")
    p.add_argument("--k", type=int, default=3, help="Top-K cutoff (default: 3).")
    p.add_argument(
        "--out",
        default="reports/retrieval_eval_rows.csv",
        help="Per-query result CSV path.",
    )
    p.add_argument(
        "--summary-out",
        default="reports/retrieval_eval_summary.txt",
        help="Summary TXT path.",
    )
    return p.parse_args()


def split_keys(raw: str) -> List[str]:
    if raw is None:
        return []
    text = str(raw).strip().strip('"').strip("'")
    if not text:
        return []
    # Support both English and Chinese separators.
    text = text.replace("；", ";").replace("，", ";")
    out: List[str] = []
    for part in text.split(";"):
        key = part.strip().strip('"').strip("'")
        if key:
            out.append(key)
    return out


def unique_keep_order(items: List[str]) -> List[str]:
    seen = set()
    out = []
    for x in items:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


def hit_at_1(pred: List[str], rel_set: set) -> float:
    if not pred:
        return 0.0
    return 1.0 if pred[0] in rel_set else 0.0


def precision_at_k(pred: List[str], rel_set: set, k: int) -> float:
    top = pred[:k]
    if not top:
        return 0.0
    hits = sum(1 for x in top if x in rel_set)
    return hits / float(len(top))


def recall_at_k(pred: List[str], rel_set: set, k: int) -> float:
    if not rel_set:
        return 0.0
    hits = sum(1 for x in pred[:k] if x in rel_set)
    return hits / float(len(rel_set))


def ap_at_k(pred: List[str], rel_set: set, k: int) -> float:
    if not rel_set:
        return 0.0
    top = pred[:k]
    hits = 0
    sum_prec = 0.0
    for i, x in enumerate(top, start=1):
        if x in rel_set:
            hits += 1
            sum_prec += hits / float(i)
    denom = max(1, min(len(rel_set), k))
    return sum_prec / float(denom)


def ndcg_at_k(pred: List[str], rel_set: set, k: int) -> float:
    top = pred[:k]
    if not rel_set or not top:
        return 0.0
    dcg = 0.0
    for i, x in enumerate(top, start=1):
        rel = 1.0 if x in rel_set else 0.0
        dcg += rel / math.log2(i + 1.0)
    ideal_rel_cnt = min(k, len(rel_set))
    idcg = sum(1.0 / math.log2(i + 1.0) for i in range(1, ideal_rel_cnt + 1))
    if idcg <= 0.0:
        return 0.0
    return dcg / idcg


def load_gt(path: Path) -> Dict[str, Dict[str, List[str]]]:
    out: Dict[str, Dict[str, List[str]]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        for r in rd:
            qid = (r.get("query_id") or "").strip()
            if not qid:
                continue
            # Standard mode:
            #   query_id,query_key,relevant_keys
            # Short mode (compatible with user's current file):
            #   query_id,query_key
            #   where query_key cell is "query;rel1;rel2;..."
            qkey_cell = (r.get("query_key") or r.get("query_mediaKey") or "").strip()
            rel_cell = (r.get("relevant_keys") or "").strip()

            if rel_cell:
                qkey = qkey_cell
                rel = unique_keep_order(split_keys(rel_cell))
            else:
                parts = unique_keep_order(split_keys(qkey_cell))
                if len(parts) >= 2:
                    qkey = parts[0]
                    rel = parts[1:]
                else:
                    qkey = qkey_cell
                    rel = []
            out[qid] = {"query_key": qkey, "relevant": rel}
    return out


def load_pred(path: Path) -> Dict[str, List[str]]:
    out: Dict[str, List[str]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        for r in rd:
            qid = (r.get("query_id") or "").strip()
            if not qid:
                continue
            preds = unique_keep_order(split_keys(r.get("topk_keys", "")))
            out[qid] = preds
    return out


def main() -> int:
    args = parse_args()
    gt_path = Path(args.gt)
    pred_path = Path(args.pred)
    out_path = Path(args.out)
    summary_path = Path(args.summary_out)
    k = max(1, args.k)

    if not gt_path.exists():
        raise FileNotFoundError(f"GT CSV not found: {gt_path}")
    if not pred_path.exists():
        raise FileNotFoundError(f"Prediction CSV not found: {pred_path}")

    gt = load_gt(gt_path)
    pred = load_pred(pred_path)

    rows = []
    for qid, item in gt.items():
        rel = item["relevant"]
        rel_set = set(rel)
        preds = pred.get(qid, [])
        h1 = hit_at_1(preds, rel_set)
        p = precision_at_k(preds, rel_set, k)
        r = recall_at_k(preds, rel_set, k)
        ap = ap_at_k(preds, rel_set, k)
        ndcg = ndcg_at_k(preds, rel_set, k)
        hits = sum(1 for x in preds[:k] if x in rel_set)
        rows.append(
            {
                "query_id": qid,
                "query_key": item["query_key"],
                "num_relevant": len(rel),
                "pred_count": len(preds),
                "k": k,
                "hits_at_k": hits,
                "hit_at_1": f"{h1:.6f}",
                "precision_at_k": f"{p:.6f}",
                "recall_at_k": f"{r:.6f}",
                "ap_at_k": f"{ap:.6f}",
                "ndcg_at_k": f"{ndcg:.6f}",
                "first_pred": preds[0] if preds else "",
            }
        )

    n = len(rows)
    if n == 0:
        raise RuntimeError("No valid GT queries found.")

    def avg(key: str) -> float:
        return sum(float(r[key]) for r in rows) / float(n)

    hit1 = avg("hit_at_1")
    p_at_k = avg("precision_at_k")
    r_at_k = avg("recall_at_k")
    map_at_k = avg("ap_at_k")
    ndcg = avg("ndcg_at_k")
    missing_pred = sum(1 for r in rows if int(r["pred_count"]) == 0)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        wr = csv.DictWriter(
            f,
            fieldnames=[
                "query_id",
                "query_key",
                "num_relevant",
                "pred_count",
                "k",
                "hits_at_k",
                "hit_at_1",
                "precision_at_k",
                "recall_at_k",
                "ap_at_k",
                "ndcg_at_k",
                "first_pred",
            ],
        )
        wr.writeheader()
        wr.writerows(rows)

    summary_lines = [
        f"gt_file={gt_path}",
        f"pred_file={pred_path}",
        f"rows_file={out_path}",
        f"queries={n}",
        f"k={k}",
        f"missing_pred_queries={missing_pred}",
        "",
        f"Hit@1={hit1:.4f}",
        f"Precision@{k}={p_at_k:.4f}",
        f"Recall@{k}={r_at_k:.4f}",
        f"mAP@{k}={map_at_k:.4f}",
        f"nDCG@{k}={ndcg:.4f}",
    ]
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")

    print(f"Wrote rows: {out_path}")
    print(f"Wrote summary: {summary_path}")
    print(f"Hit@1={hit1:.4f}")
    print(f"Precision@{k}={p_at_k:.4f}")
    print(f"Recall@{k}={r_at_k:.4f}")
    print(f"mAP@{k}={map_at_k:.4f}")
    print(f"nDCG@{k}={ndcg:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
