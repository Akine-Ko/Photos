package com.example.photos.model;

/**
 * 枚举：应用内的智能分类标签。
 */
public enum PhotoCategory {
    ALL("全部"),
    SELFIE("自拍"),
    GROUP("合照"),
    DRAWING("绘画"),
    CARD("卡证"),
    QRCODE("二维码"),
    TRAVEL("旅行"),
    FOOD("美食"),
    TEXT("文本"),
    LIFE("生活");

    private final String displayName;

    PhotoCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
