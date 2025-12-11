#!/usr/bin/env python3
"""
Smoke test for the zh->en translation assets, following the flow in
OnnxZhEnTranslator.

Usage:
  python tools/test_translation_opus.py --text "<chinese text>"
  python tools/test_translation_opus.py --text "... " --base app/src/main/assets/opus_mt_zh_en
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable, List, Tuple

import numpy as np
import onnxruntime as ort
import sentencepiece as spm


DEFAULT_PAD_ID = 65000
DEFAULT_EOS_ID = 0
DEFAULT_DECODER_START_ID = 65000
MAX_SRC_TOKENS = 128
MAX_NEW_TOKENS = 64


def contains_cjk(text: str) -> bool:
    for ch in text:
        code = ord(ch)
        if 0x4E00 <= code <= 0x9FFF:
            return True
        if 0x3400 <= code <= 0x4DBF:
            return True
        if 0x20000 <= code <= 0x2A6DF:
            return True
        if 0xF900 <= code <= 0xFAFF:
            return True
    return False


def pick_base_dir(custom: str | None) -> Path:
    candidates: List[Path] = []
    if custom:
        candidates.append(Path(custom))
    candidates.extend(
        [
            Path("app/src/main/assets/models/opus_mt_zh_en"),
            Path("app/src/main/assets/opus_mt_zh_en"),
            Path("models--opus-mt"),
        ]
    )
    for path in candidates:
        if path.is_dir():
            return path
    searched = "\n  ".join(str(p) for p in candidates)
    raise FileNotFoundError(f"Cannot find opus_mt_zh_en assets. Searched:\n  {searched}")


def load_config(base_dir: Path) -> Tuple[int, int, int]:
    config_path = base_dir / "config.json"
    with config_path.open("r", encoding="utf-8") as fh:
        data = json.load(fh)
    pad_id = int(data.get("pad_token_id", DEFAULT_PAD_ID))
    eos_id = int(data.get("eos_token_id", DEFAULT_EOS_ID))
    decoder_start_id = int(data.get("decoder_start_token_id", DEFAULT_DECODER_START_ID))
    return pad_id, eos_id, decoder_start_id


def encode_src(sp: spm.SentencePieceProcessor, text: str, eos_id: int) -> List[int]:
    ids = sp.encode(text, out_type=int)
    if not ids:
        return []
    ids.append(eos_id)
    if len(ids) > MAX_SRC_TOKENS:
        ids = ids[:MAX_SRC_TOKENS]
    return ids


def decode_tgt(sp: spm.SentencePieceProcessor, ids: Iterable[int], pad_id: int, eos_id: int, decoder_start_id: int) -> str:
    tokens = list(ids)
    if tokens and tokens[0] == decoder_start_id:
        tokens = tokens[1:]
    while tokens and (tokens[-1] == pad_id or tokens[-1] == eos_id):
        tokens.pop()
    if not tokens:
        return ""
    return sp.decode(tokens)


def greedy_decode(
    decoder: ort.InferenceSession,
    encoder_hidden_states: np.ndarray,
    encoder_attention_mask: np.ndarray,
    pad_id: int,
    eos_id: int,
    decoder_start_id: int,
) -> List[int]:
    generated: List[int] = [decoder_start_id]
    for _ in range(MAX_NEW_TOKENS):
        decoder_input_ids = np.asarray([generated], dtype=np.int64)
        inputs = {
            "input_ids": decoder_input_ids,
            "encoder_hidden_states": encoder_hidden_states,
            "encoder_attention_mask": encoder_attention_mask,
        }
        logits = decoder.run(None, inputs)[0]  # [1, tgt_len, vocab_size]
        last_logits = logits[0, -1]
        next_id = int(np.argmax(last_logits))
        if next_id == eos_id or next_id == pad_id:
            break
        generated.append(next_id)
    return generated


def translate(text: str, base_dir: Path) -> str:
    if text is None:
        return ""
    trimmed = text.strip()
    if not trimmed:
        return trimmed
    if not contains_cjk(trimmed):
        return trimmed

    pad_id, eos_id, decoder_start_id = load_config(base_dir)
    encoder_path = base_dir / "encoder.onnx"
    decoder_path = base_dir / "decoder.onnx"
    src_sp_path = base_dir / "source.spm"
    tgt_sp_path = base_dir / "target.spm"

    if not encoder_path.exists() or not decoder_path.exists():
        raise FileNotFoundError("encoder.onnx or decoder.onnx is missing in " + str(base_dir))

    encoder = ort.InferenceSession(str(encoder_path), providers=["CPUExecutionProvider"])
    decoder = ort.InferenceSession(str(decoder_path), providers=["CPUExecutionProvider"])

    src_sp = spm.SentencePieceProcessor(model_file=str(src_sp_path))
    tgt_sp = spm.SentencePieceProcessor(model_file=str(tgt_sp_path))

    src_ids = encode_src(src_sp, trimmed, eos_id)
    if not src_ids:
        return trimmed

    encoder_input_ids = np.asarray([src_ids], dtype=np.int64)
    encoder_attention_mask = np.ones_like(encoder_input_ids, dtype=np.int64)

    encoder_outputs = encoder.run(None, {"input_ids": encoder_input_ids, "attention_mask": encoder_attention_mask})
    encoder_hidden_states = encoder_outputs[0]

    decoded_ids = greedy_decode(
        decoder,
        encoder_hidden_states=encoder_hidden_states,
        encoder_attention_mask=encoder_attention_mask,
        pad_id=pad_id,
        eos_id=eos_id,
        decoder_start_id=decoder_start_id,
    )

    return decode_tgt(tgt_sp, decoded_ids, pad_id, eos_id, decoder_start_id)


def main() -> None:
    parser = argparse.ArgumentParser(description="Test zh->en translation assets via ONNX.")
    parser.add_argument("--text", required=True, help="Chinese text to translate.")
    parser.add_argument("--base", help="Path to opus_mt_zh_en asset directory. Optional.")
    args = parser.parse_args()

    base_dir = pick_base_dir(args.base)
    print(f"Using assets at: {base_dir}")
    result = translate(args.text, base_dir)
    print("Input :", args.text)
    print("Output:", result)


if __name__ == "__main__":
    main()
