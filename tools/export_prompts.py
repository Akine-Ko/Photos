#!/usr/bin/env python3
"""
Generate MobileCLIP text embeddings for the fixed category list used by the app.
Usage example:
  python tools/export_prompts.py \
    --checkpoint models--mobileclip2-s2-dfndr2b/mobileclip2_s2_dfndr2b.pt \
    --out-bin app/src/main/assets/models/clip/text_embeds_f32.bin \
    --out-labels app/src/main/assets/models/clip/category_keys.txt
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import torch

REPO_ROOT = Path(__file__).resolve().parents[1]
ML_MOBILECLIP_ROOT = REPO_ROOT / "ml-mobileclip"
if ML_MOBILECLIP_ROOT.is_dir():
    sys.path.insert(0, str(ML_MOBILECLIP_ROOT))
    oc_src = ML_MOBILECLIP_ROOT / "open_clip" / "src"
    if oc_src.is_dir():
        sys.path.insert(0, str(oc_src))

# (category, prompt)
PROMPTS = [
    ("SELFIE", "a close up selfie portrait of one person facing the camera with no printed text overlay"),
    ("GROUP", "a group photo of several friends taking a selfie together"),
    ("IDPHOTO", "an official passport style id portrait with a plain background"),
    ("DRAWING", "an illustration, a manga style drawing or a hand drawing without printed text"),
    ("CARD", "close up photo of bank cards or id cards"),
    ("QRCODE", "image showing a large qr code or barcode on paper or a phone screen"),
    ("NATURE", "a natural landscape photo showing mountains rivers or forests under bright daylight"),
    ("PLANTS", "close up photo focusing on vibrant plants flowers or leaves"),
    ("VEHICLES", "photo highlighting modern transportation vehicles such as cars trains airplanes or bikes"),
    ("ARCHITECTURE", "photo showcasing architectural exteriors such as landmark buildings city skylines monuments or modern facades"),
    ("PETS", "cute pet portrait featuring cats dogs or other domestic animals"),
    ("ELECTRONICS", "photo focusing on modern electronic devices such as smartphones tablets laptops or gaming consoles"),
    ("FOOD", "close up photo of delicious cooked food desserts or drinks on a table"),
    ("TEXT", "a text photo or a messaging screenshot mostly filled with text or forms"),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-name", default="MobileCLIP2-S2")
    parser.add_argument("--checkpoint", default=None,
                        help="Path to *.pt checkpoint (recommended to avoid network).")
    parser.add_argument("--pretrained-tag", default="dfndr2b",
                        help="Fallback open_clip pretrained tag when --checkpoint not provided.")
    parser.add_argument("--out-bin", required=True, help="Path to write float32 embeddings")
    parser.add_argument("--out-labels", required=True, help="Path to write labels text file")
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()

    import open_clip
    pretrained_value = args.checkpoint or args.pretrained_tag
    if pretrained_value is None or pretrained_value.lower() == "none":
        pretrained_value = "dfndr2b"

    model, _, _ = open_clip.create_model_and_transforms(
        args.model_name,
        pretrained=pretrained_value,
        device=args.device,
    )

    prompts = [p for _, p in PROMPTS]
    labels = [label for label, _ in PROMPTS]

    tokens = open_clip.tokenize(prompts).to(args.device)
    with torch.no_grad():
        feats = model.encode_text(tokens).float().cpu().numpy()
    feats = feats / np.clip(np.linalg.norm(feats, axis=1, keepdims=True), 1e-12, None)
    feats.astype(np.float32).tofile(args.out_bin)
    with open(args.out_labels, "w", encoding="utf-8") as f:
        for label in labels:
            f.write(label + "\n")
    print("Wrote", args.out_bin, "shape", feats.shape)
    print("Wrote labels to", args.out_labels)


if __name__ == "__main__":
    main()
