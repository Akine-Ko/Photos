#!/usr/bin/env python3
"""
Evaluate image-to-image retrieval with intent-aware GT:
  - FACE (same person)
  - SCENE (similar scene)
  - COMPOSITION (similar composition)

GT CSV format (--gt):
  query_id,query_key,intent,must_hit_keys,relevant_strong_keys,relevant_weak_keys,notes

Prediction CSV format (--pred):
  query_id,topk_keys

Key list separator: ';' (also supports Chinese separators '；' and '，').

Metrics (overall + per-intent):
  Hit@1(must)
  Recall@K(strong)
  Recall@K(all=strong+weak)
  Precision@K(all)
  MRR@K(must)
  nDCG@K(all, strong=2 weak=1)
"""

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Set


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Evaluate intent-aware image retrieval.")
    p.add_argument("--gt", required=True, help="GT CSV path.")
    p.add_argument("--pred", required=True, help="Prediction CSV path.")
    p.add_argument("--k", type=int, default=3, help="Top-K cutoff.")
    p.add_argument(
        "--out",
        default="reports/image_intent_eval_rows.csv",
        help="Per-query output CSV path.",
    )
    p.add_argument(
        "--summary-out",
        default="reports/image_intent_eval_summary.txt",
        help="Summary TXT path.",
    )
    return p.parse_args()


def split_keys(raw: str) -> List[str]:
    if raw is None:
        return []
    text = str(raw).strip().strip('"').strip("'")
    if not text:
        return []
    text = text.replace("；", ";").replace("，", ";")
    out: List[str] = []
    for part in text.split(";"):
        key = part.strip().strip('"').strip("'")
        if key:
            out.append(key)
    return out


def uniq_keep_order(items: List[str]) -> List[str]:
    seen = set()
    out = []
    for x in items:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


def first_hit_rank(pred: List[str], target: Set[str], k: int) -> int:
    if not target:
        return 0
    for i, x in enumerate(pred[:k], start=1):
        if x in target:
            return i
    return 0


def recall_at_k(pred: List[str], target: Set[str], k: int) -> float:
    if not target:
        return 0.0
    hits = sum(1 for x in pred[:k] if x in target)
    return hits / float(len(target))


def precision_at_k(pred: List[str], target: Set[str], k: int) -> float:
    top = pred[:k]
    if not top:
        return 0.0
    hits = sum(1 for x in top if x in target)
    return hits / float(len(top))


def ndcg_at_k(pred: List[str], strong: Set[str], weak: Set[str], k: int) -> float:
    top = pred[:k]
    if not top:
        return 0.0

    def rel(x: str) -> float:
        if x in strong:
            return 2.0
        if x in weak:
            return 1.0
        return 0.0

    dcg = 0.0
    for i, x in enumerate(top, start=1):
        dcg += rel(x) / math.log2(i + 1.0)

    ideal_rels = [2.0] * min(k, len(strong))
    rem = k - len(ideal_rels)
    if rem > 0:
        ideal_rels.extend([1.0] * min(rem, len(weak)))
    if not ideal_rels:
        return 0.0
    idcg = sum(r / math.log2(i + 1.0) for i, r in enumerate(ideal_rels, start=1))
    if idcg <= 0.0:
        return 0.0
    return dcg / idcg


def load_gt(path: Path) -> Dict[str, dict]:
    gt: Dict[str, dict] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        for r in rd:
            qid = (r.get("query_id") or "").strip()
            if not qid:
                continue
            intent = (r.get("intent") or "").strip().upper()
            if not intent:
                intent = "UNSPECIFIED"
            must = set(split_keys(r.get("must_hit_keys", "")))
            strong = set(split_keys(r.get("relevant_strong_keys", "")))
            weak = set(split_keys(r.get("relevant_weak_keys", "")))
            all_rel = set(strong) | set(weak)
            gt[qid] = {
                "query_key": (r.get("query_key") or "").strip(),
                "intent": intent,
                "must": must,
                "strong": strong,
                "weak": weak,
                "all_rel": all_rel,
                "notes": (r.get("notes") or "").strip(),
            }
    return gt


def load_pred(path: Path) -> Dict[str, List[str]]:
    pred: Dict[str, List[str]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        for r in rd:
            qid = (r.get("query_id") or "").strip()
            if not qid:
                continue
            keys = uniq_keep_order(split_keys(r.get("topk_keys", "")))
            pred[qid] = keys
    return pred


def main() -> int:
    args = parse_args()
    gt_path = Path(args.gt)
    pred_path = Path(args.pred)
    out_path = Path(args.out)
    summary_path = Path(args.summary_out)
    k = max(1, int(args.k))

    if not gt_path.exists():
        raise FileNotFoundError(f"GT CSV not found: {gt_path}")
    if not pred_path.exists():
        raise FileNotFoundError(f"Prediction CSV not found: {pred_path}")

    gt = load_gt(gt_path)
    pred = load_pred(pred_path)
    if not gt:
        raise RuntimeError("GT has no valid rows.")

    rows = []
    for qid, g in gt.items():
        p = pred.get(qid, [])
        must = g["must"]
        strong = g["strong"]
        weak = g["weak"]
        all_rel = g["all_rel"]

        rank = first_hit_rank(p, must, k)
        hit1_must = 1.0 if (p and must and p[0] in must) else 0.0
        mrr_must = (1.0 / rank) if rank > 0 else 0.0
        r_strong = recall_at_k(p, strong, k)
        r_all = recall_at_k(p, all_rel, k)
        p_all = precision_at_k(p, all_rel, k)
        ndcg = ndcg_at_k(p, strong, weak, k)
        hits_all = sum(1 for x in p[:k] if x in all_rel)

        rows.append(
            {
                "query_id": qid,
                "intent": g["intent"],
                "query_key": g["query_key"],
                "k": k,
                "pred_count": len(p),
                "must_count": len(must),
                "strong_count": len(strong),
                "weak_count": len(weak),
                "hits_all_at_k": hits_all,
                "hit_at_1_must": f"{hit1_must:.6f}",
                "recall_at_k_strong": f"{r_strong:.6f}",
                "recall_at_k_all": f"{r_all:.6f}",
                "precision_at_k_all": f"{p_all:.6f}",
                "mrr_at_k_must": f"{mrr_must:.6f}",
                "ndcg_at_k_all": f"{ndcg:.6f}",
                "first_pred": p[0] if p else "",
            }
        )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        wr = csv.DictWriter(
            f,
            fieldnames=[
                "query_id",
                "intent",
                "query_key",
                "k",
                "pred_count",
                "must_count",
                "strong_count",
                "weak_count",
                "hits_all_at_k",
                "hit_at_1_must",
                "recall_at_k_strong",
                "recall_at_k_all",
                "precision_at_k_all",
                "mrr_at_k_must",
                "ndcg_at_k_all",
                "first_pred",
            ],
        )
        wr.writeheader()
        wr.writerows(rows)

    def agg(items: List[dict], key: str) -> float:
        if not items:
            return 0.0
        return sum(float(x[key]) for x in items) / float(len(items))

    by_intent: Dict[str, List[dict]] = defaultdict(list)
    for r in rows:
        by_intent[r["intent"]].append(r)

    summary_lines = [
        f"gt_file={gt_path}",
        f"pred_file={pred_path}",
        f"rows_file={out_path}",
        f"queries={len(rows)}",
        f"k={k}",
        "",
        "[overall]",
        f"Hit@1(must)={agg(rows, 'hit_at_1_must'):.4f}",
        f"MRR@{k}(must)={agg(rows, 'mrr_at_k_must'):.4f}",
        f"Recall@{k}(strong)={agg(rows, 'recall_at_k_strong'):.4f}",
        f"Recall@{k}(all)={agg(rows, 'recall_at_k_all'):.4f}",
        f"Precision@{k}(all)={agg(rows, 'precision_at_k_all'):.4f}",
        f"nDCG@{k}(all)={agg(rows, 'ndcg_at_k_all'):.4f}",
        "",
        "[per_intent]",
    ]

    for intent in sorted(by_intent.keys()):
        items = by_intent[intent]
        summary_lines.extend(
            [
                f"{intent}: n={len(items)}",
                f"  Hit@1(must)={agg(items, 'hit_at_1_must'):.4f}",
                f"  MRR@{k}(must)={agg(items, 'mrr_at_k_must'):.4f}",
                f"  Recall@{k}(strong)={agg(items, 'recall_at_k_strong'):.4f}",
                f"  Recall@{k}(all)={agg(items, 'recall_at_k_all'):.4f}",
                f"  Precision@{k}(all)={agg(items, 'precision_at_k_all'):.4f}",
                f"  nDCG@{k}(all)={agg(items, 'ndcg_at_k_all'):.4f}",
            ]
        )

    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")

    print(f"Wrote rows: {out_path}")
    print(f"Wrote summary: {summary_path}")
    print(f"Hit@1(must)={agg(rows, 'hit_at_1_must'):.4f}")
    print(f"MRR@{k}(must)={agg(rows, 'mrr_at_k_must'):.4f}")
    print(f"Recall@{k}(strong)={agg(rows, 'recall_at_k_strong'):.4f}")
    print(f"Recall@{k}(all)={agg(rows, 'recall_at_k_all'):.4f}")
    print(f"Precision@{k}(all)={agg(rows, 'precision_at_k_all'):.4f}")
    print(f"nDCG@{k}(all)={agg(rows, 'ndcg_at_k_all'):.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

