#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Rebuild category_keys.txt and text_embeds_f32.bin from prompts.json for CN-CLIP.

Usage (run from project root):
    python tools/regen_clip_prompts.py \
        --prompts app/src/main/assets/models/clip/prompts/prompts.json \
        --categories-out app/src/main/assets/models/clip/category_keys.txt \
        --embeds-out app/src/main/assets/models/clip/text_embeds_f32.bin \
        --model-name ViT-B-16 \
        --model-root models--cn_clip/chinese-clip-vit-base-patch16

It reads prompts.json (UTF-8, ensure_ascii=False), averages embeddings per
category (L2-normalized), and writes float32 binary embeddings plus category list.
"""

import argparse
import json
import struct
import sys
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--prompts",
        default="app/src/main/assets/models/clip/prompts/prompts.json",
        help="Path to prompts.json (UTF-8, ensure_ascii=False).",
    )
    parser.add_argument(
        "--categories-out",
        default="app/src/main/assets/models/clip/category_keys.txt",
        help="Output path for category list (one per line, UTF-8).",
    )
    parser.add_argument(
        "--embeds-out",
        default="app/src/main/assets/models/clip/text_embeds_f32.bin",
        help="Output path for text embeddings (float32 binary).",
    )
    parser.add_argument(
        "--model-name",
        default="ViT-B-16",
        choices=["ViT-B-16", "ViT-L-14", "ViT-L-14-336", "ViT-H-14", "RN50"],
        help="Chinese-CLIP model name.",
    )
    parser.add_argument(
        "--model-root",
        default="models--cn_clip/chinese-clip-vit-base-patch16",
        help="Local directory containing the model weights (download_root).",
    )
    parser.add_argument(
        "--repo-path",
        default="../Chinese-CLIP",
        help="Path to Chinese-CLIP repo for import (will be added to sys.path).",
    )
    parser.add_argument(
        "--device",
        default="cuda",
        help="torch device to use; defaults to cuda if available, else cpu.",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    repo_path = Path(args.repo_path).resolve()
    if str(repo_path) not in sys.path:
        sys.path.insert(0, str(repo_path))

    import torch
    import cn_clip.clip as clip
    from cn_clip.clip import load_from_name

    prompts_path = Path(args.prompts)
    data = json.loads(prompts_path.read_text(encoding="utf-8"))
    categories = data["categories"]
    prompts = data["prompts"]

    # Write categories file
    categories_out = Path(args.categories_out)
    categories_out.write_text("\n".join(categories), encoding="utf-8")

    # Select device
    device = (
        args.device
        if args.device != "cuda"
        else ("cuda" if torch.cuda.is_available() else "cpu")
    )

    model, _ = load_from_name(
        args.model_name,
        device=device,
        download_root=str(Path(args.model_root).expanduser()),
    )
    model.eval()

    embeds = []
    for cat in categories:
        texts = prompts[cat]
        tokens = clip.tokenize(texts).to(device)
        with torch.no_grad():
            txt = model.encode_text(tokens)
            txt = txt / txt.norm(dim=-1, keepdim=True)
        mean = txt.mean(dim=0)
        mean = mean / mean.norm()
        embeds.append(mean.cpu().float())

    embeds = torch.stack(embeds, dim=0)

    embeds_out = Path(args.embeds_out)
    with embeds_out.open("wb") as f:
        for row in embeds:
            f.write(struct.pack("<{}f".format(len(row)), *row.tolist()))

    print(f"categories: {len(categories)} -> {categories_out}")
    print(f"embeds: {embeds.shape} -> {embeds_out}")
    print(f"model: {args.model_name} device: {device}")


if __name__ == "__main__":
    main()
