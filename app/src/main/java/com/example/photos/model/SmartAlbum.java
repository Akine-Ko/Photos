package com.example.photos.model;

/**
 * “智能分类”页面使用的聚合数据模型，描述一个 AI相册。
 */
public class SmartAlbum {

    private final PhotoCategory category;
    private final String title;
    private final String subtitle;
    private final String aiSummary;
    private final String coverUrl;
    private final int assetCount;

    public SmartAlbum(PhotoCategory category,
                      String title,
                      String subtitle,
                      String aiSummary,
                      String coverUrl,
                      int assetCount) {
        this.category = category;
        this.title = title;
        this.subtitle = subtitle;
        this.aiSummary = aiSummary;
        this.coverUrl = coverUrl;
        this.assetCount = assetCount;
    }

    public PhotoCategory getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public int getAssetCount() {
        return assetCount;
    }
}

