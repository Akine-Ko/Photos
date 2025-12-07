# -*- coding: utf-8 -*-
import sys, struct
from pathlib import Path
import torch
sys.path.insert(0, r"E:\\Project\\Chinese-CLIP")
import cn_clip.clip as clip
from cn_clip.clip import load_from_name

# Category list
categories = [
    "自拍照",
    "多人合影",
    "证件照",
    "手绘或插画",
    "卡片或票据",
    "二维码或条形码",
    "自然风景",
    "花草植物",
    "交通工具车辆",
    "建筑物",
    "宠物动物",
    "电子产品",
    "美食",
    "文档或文字图片",
]

prompts = {
    "自拍照": [
        "自拍照", "手机自拍", "近距离自拍", "室内自拍", "自然光自拍", "社交头像自拍", "镜子自拍"
    ],
    "多人合影": [
        "多人合影", "集体合照", "朋友合照", "家庭合影", "聚会合影", "婚礼合影", "毕业合影"
    ],
    "证件照": [
        "证件照", "身份证照片", "护照照片", "签证照片", "一寸证件照", "二寸证件照",
        "红底证件照", "蓝底证件照", "白底证件照", "正面直视证件照", "平光证件照", "无表情证件照"
    ],
    "手绘或插画": [
        "手绘插画", "线稿插画", "卡通插画", "漫画风插画", "水彩手绘", "儿童画", "插画作品"
    ],
    "卡片或票据": [
        "票据照片", "收据照片", "发票照片", "车票照片", "登机牌照片", "银行卡照片", "证件卡片照片", "纸质票据"
    ],
    "二维码或条形码": [
        "二维码图片", "条形码图片", "付款二维码", "包装上的条形码", "扫码界面", "商品条形码"
    ],
    "自然风景": [
        "山川风景照", "海边风景", "日出风景", "夕阳风景", "森林风景", "湖泊风景", "雪山风景"
    ],
    "花草植物": [
        "花朵特写", "植物特写", "盆栽照片", "花园场景", "绿植叶子特写", "鲜花照片"
    ],
    "交通工具车辆": [
        "汽车照片", "公交车照片", "火车照片", "飞机照片", "地铁车厢照片", "自行车照片", "摩托车照片", "卡车照片"
    ],
    "建筑物": [
        "建筑外观", "室内建筑空间", "城市建筑", "高楼大厦", "古建筑", "桥梁建筑", "街景建筑"
    ],
    "宠物动物": [
        "猫咪照片", "狗狗照片", "宠物合影", "鸟类照片", "动物园动物", "小动物特写"
    ],
    "电子产品": [
        "手机照片", "笔记本电脑", "平板电脑", "相机设备", "耳机", "智能手表", "电子设备特写"
    ],
    "美食": [
        "餐厅菜品", "家常菜", "甜品蛋糕", "饮料咖啡", "水果拼盘", "街头小吃", "美食特写"
    ],
    "文档或文字图片": [
        "文档扫描件", "文字截图", "书本页面", "PDF文档图片", "PPT界面", "手写笔记", "打印文件照片"
    ],
}

# Write category file
cat_path = Path("app/src/main/assets/models/clip/category_keys.txt")
cat_path.write_text("\n".join(categories), encoding="utf-8")
print("category_keys.txt written:")
print(cat_path.read_text(encoding='utf-8'))

# Load model
device = "cuda" if torch.cuda.is_available() else "cpu"
model, preprocess = load_from_name(
    "ViT-B-16",
    device=device,
    download_root=r"E:\\Project\\Photos\\models--cn_clip\\chinese-clip-vit-base-patch16",
)
model.eval()

embeds = []
for cat in categories:
    tok = clip.tokenize(prompts[cat]).to(device)
    with torch.no_grad():
        txt = model.encode_text(tok)
        txt = txt / txt.norm(dim=-1, keepdim=True)
    mean = txt.mean(dim=0)
    mean = mean / mean.norm()
    embeds.append(mean.cpu().float())

embeds = torch.stack(embeds, dim=0)
print("embeds shape", embeds.shape)

out_path = Path("app/src/main/assets/models/clip/text_embeds_f32.bin")
with out_path.open("wb") as f:
    for row in embeds:
        f.write(struct.pack('<{}f'.format(len(row)), *row.tolist()))
print("wrote", out_path, "bytes", out_path.stat().st_size)
