from huggingface_hub import hf_hub_download

# YuNet
yunet_path = hf_hub_download(
    repo_id="opencv/face_detection_yunet",
    filename="face_detection_yunet_2023mar.onnx",
    local_dir="./models-opencv",
)

# SFace
sface_path = hf_hub_download(
    repo_id="opencv/face_recognition_sface",
    filename="face_recognition_sface_2021dec.onnx",
    local_dir="./models-opencv",
)

print("YuNet:", yunet_path)
print("SFace:", sface_path)