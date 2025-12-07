package com.example.photos.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 表示一张经过智能分析的照片，包含标签、描述与分类。
 */
public class Photo {

    private final String id;
    private final String title;
    private final String description;
    private final String captureDate;
    private final String location;
    private final List<String> tags;
    private final String imageUrl;
    private final boolean favorite;
    private final PhotoCategory category;

    public Photo(String id,
                 String title,
                 String description,
                 String captureDate,
                 String location,
                 List<String> tags,
                 String imageUrl,
                 boolean favorite,
                 PhotoCategory category) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.captureDate = captureDate;
        this.location = location;
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        this.imageUrl = imageUrl;
        this.favorite = favorite;
        this.category = category;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCaptureDate() {
        return captureDate;
    }

    public String getLocation() {
        return location;
    }

    public List<String> getTags() {
        return Collections.unmodifiableList(tags);
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public PhotoCategory getCategory() {
        return category;
    }

    /**
     * 关键字匹配逻辑：在标题/描述/地点/标签中做包含判断。
     */
    public boolean matchesQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String lower = query.trim().toLowerCase(Locale.getDefault());
        if ((title != null && title.toLowerCase(Locale.getDefault()).contains(lower))
                || (description != null && description.toLowerCase(Locale.getDefault()).contains(lower))
                || (location != null && location.toLowerCase(Locale.getDefault()).contains(lower))) {
            return true;
        }
        for (String tag : tags) {
            if (tag != null && tag.toLowerCase(Locale.getDefault()).contains(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类别过滤：ALL 或 null 时默认不过滤。
     */
    public boolean isInCategory(PhotoCategory filter) {
        if (filter == null || filter == PhotoCategory.ALL) {
            return true;
        }
        return Objects.equals(category, filter);
    }
}



