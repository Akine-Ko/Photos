package com.example.photos.ui.albums;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps internal category keys to localized display names.
 */
public final class CategoryDisplay {
    private static final Map<String, String> MAP = new HashMap<>();

    static {
        MAP.put("ALL", "\u5168\u90e8");
        MAP.put("SELFIE", "\u81ea\u62cd");
        MAP.put("GROUP", "\u5408\u7167");
        MAP.put("QRCODE", "\u4e8c\u7ef4\u7801");
        MAP.put("IDPHOTO", "\u5361\u8bc1");
        MAP.put("CARD", "\u5361\u8bc1");
        MAP.put("TEXT", "\u6587\u672c");
        MAP.put("NATURE", "\u81ea\u7136\u98ce\u5149");
        MAP.put("DRAWING", "\u7ed8\u753b");
        MAP.put("ARCHITECTURE", "\u5efa\u7b51");
        MAP.put("PLANTS", "\u690d\u7269");
        MAP.put("FOOD", "\u7f8e\u98df");
        MAP.put("ELECTRONICS", "\u7535\u5b50\u4ea7\u54c1");
        MAP.put("PETS", "\u5ba0\u7269");

        MAP.put("TRAVEL", "\u65c5\u884c");
        MAP.put("LIFE", "\u751f\u6d3b");
        MAP.put("VEHICLES", "\u4ea4\u901a\u5de5\u5177");
        MAP.put("SPORTS", "\u8fd0\u52a8");
        MAP.put("NIGHT", "\u591c\u666f");
        MAP.put("SUNSET", "\u65e5\u843d");
        MAP.put("BEACH", "\u6c99\u6ee9");
        MAP.put("ART", "\u827a\u672f");
    }

    private CategoryDisplay() {}

    public static String displayOf(String key) {
        if (key == null) return "";
        String k = key.toUpperCase(Locale.ROOT);
        if (k.startsWith("AUTO_CLUSTER_")) {
            String suffix = k.substring("AUTO_CLUSTER_".length());
            return "\u672a\u547d\u540d\u805a\u7c7b" + suffix;
        }
        String v = MAP.get(k);
        return v != null ? v : key;
    }
}

