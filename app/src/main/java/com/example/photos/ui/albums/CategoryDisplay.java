package com.example.photos.ui.albums;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps internal category keys to localized display names.
 */
final class CategoryDisplay {
    private static final Map<String, String> MAP = new HashMap<>();

    static {
        MAP.put("ALL", "全部");
        MAP.put("SELFIE", "自拍");
        MAP.put("GROUP", "合照");
        MAP.put("IDPHOTO", "卡证");
        MAP.put("TRAVEL", "旅行");
        MAP.put("FOOD", "美食");
        MAP.put("TEXT", "文本");
        MAP.put("CARD", "卡证");
        MAP.put("QRCODE", "二维码");
        MAP.put("LIFE", "生活");
        MAP.put("DRAWING", "绘画");
        MAP.put("PETS", "宠物");
        MAP.put("PLANTS", "植物");
        MAP.put("NATURE", "自然风光");
        MAP.put("VEHICLES", "交通工具");
        MAP.put("ELECTRONICS", "电子产品");
        MAP.put("SPORTS", "运动");
        MAP.put("NIGHT", "夜景");
        MAP.put("SUNSET", "日落");
        MAP.put("BEACH", "沙滩");
        MAP.put("ARCHITECTURE", "建筑");
        MAP.put("ART", "艺术");
    }

    private CategoryDisplay() {}

    static String displayOf(String key) {
        if (key == null) return "";
        String k = key.toUpperCase(Locale.ROOT);
        if (k.startsWith("AUTO_CLUSTER_")) {
            String suffix = k.substring("AUTO_CLUSTER_".length());
            return "未命名聚类#" + suffix;
        }
        String v = MAP.get(k);
        return v != null ? v : key;
    }
}
