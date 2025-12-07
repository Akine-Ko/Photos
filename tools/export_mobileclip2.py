#!/usr/bin/env python3
"""
Minimal exporter that follows axera/ml-mobileclip style to dump ONNX encoders.
"""
import argparse
from pathlib import Path

import torch
from PIL import Image

import open_clip

try:
    import mobileclip
    HAS_MOBILECLIP = True
except ImportError:
    HAS_MOBILECLIP = False

try:
    from mobileclip.modules.common.mobileone import reparameterize_model
except ImportError:
    reparameterize_model = None


def is_v1(model_name: str) -> bool:
    return model_name in ("mobileclip_b", "mobileclip_s0", "mobileclip_s1", "mobileclip_s2")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-name", default="MobileCLIP2-S2")
    parser.add_argument("--checkpoint", required=True, help="Path to *.pt checkpoint")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--image", default=None, help="Optional image path to verify preprocess.")
    parser.add_argument("--out-dir", default="onnx_exports")
    parser.add_argument("--opset", type=int, default=14)
    args = parser.parse_args()

    Path(args.out_dir).mkdir(parents=True, exist_ok=True)

    if is_v1(args.model_name):
        if not HAS_MOBILECLIP:
            raise RuntimeError("mobileclip package not installed; cannot export v1 models.")
        model, _, preprocess = mobileclip.create_model_and_transforms(
            args.model_name, pretrained=args.checkpoint
        )
        tokenizer = mobileclip.get_tokenizer(args.model_name)
        image_encoder = model.image_encoder
        text_encoder = model.text_encoder
    else:
        model, _, preprocess = open_clip.create_model_and_transforms(
            args.model_name, device=args.device, pretrained=args.checkpoint
        )
        tokenizer = open_clip.get_tokenizer(args.model_name)
        if reparameterize_model is None:
            raise RuntimeError("mobileclip reparameterize_model not available.")
        model = reparameterize_model(model.eval())
        image_encoder = model.visual
        text_encoder = model.text

    image_encoder.eval()
    text_encoder.eval()

    # Optional verification run
    if args.image:
        img = preprocess(Image.open(args.image).convert("RGB")).unsqueeze(0)
        txt = tokenizer(["a diagram", "a dog", "a cat"])
    else:
        img = torch.rand(1, 3, 256, 256)
        txt = tokenizer(["dummy"])

    with torch.no_grad():
        image_features = model.encode_image(img.to(args.device))
        text_features = model.encode_text(txt.to(args.device))
        image_features /= image_features.norm(dim=-1, keepdim=True)
        text_features /= text_features.norm(dim=-1, keepdim=True)
        print("Sanity check dot:", (image_features @ text_features.T)[0])

    img_path = Path(args.out_dir) / f"{args.model_name}_image_encoder.onnx"
    txt_path = Path(args.out_dir) / f"{args.model_name}_text_encoder.onnx"

    torch.onnx.export(
        image_encoder,
        img,
        str(img_path),
        input_names=["image"],
        output_names=["unnorm_image_features"],
        export_params=True,
        opset_version=args.opset,
    )
    torch.onnx.export(
        text_encoder,
        txt,
        str(txt_path),
        input_names=["text"],
        output_names=["unnorm_text_features"],
        export_params=True,
        opset_version=args.opset,
    )
    print("Saved image encoder to", img_path)
    print("Saved text encoder to", txt_path)


if __name__ == "__main__":
    main()
