from huggingface_hub import hf_hub_download
import os
import shutil

REPO_ID = "onnx-community/opus-mt-zh-en"

# 按你工程路径改
TARGET_DIR = r"E:\Project\Photos\models--opus-mt"
os.makedirs(TARGET_DIR, exist_ok=True)

def download_file(filename, subdir=""):
    if subdir:
        remote_path = f"{subdir}/{filename}"
    else:
        remote_path = filename
    local_path = hf_hub_download(repo_id=REPO_ID, filename=remote_path)
    dst = os.path.join(TARGET_DIR, filename)
    print(f"copy {local_path} -> {dst}")
    shutil.copy(local_path, dst)
    return dst

# 1) SentencePiece 模型
download_file("source.spm")
download_file("target.spm")

# 2) ONNX 模型（用 fp16 版，体积稍小）
enc = download_file("encoder_model_fp16.onnx", subdir="onnx")
dec = download_file("decoder_model_fp16.onnx", subdir="onnx")

# 3) 重命名成我们在代码里用的名字
os.rename(enc, os.path.join(TARGET_DIR, "encoder.onnx"))
os.rename(dec, os.path.join(TARGET_DIR, "decoder.onnx"))

print("Done. Files are in", TARGET_DIR)