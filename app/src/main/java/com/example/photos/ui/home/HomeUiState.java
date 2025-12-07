package com.example.photos.ui.home;

import com.example.photos.model.PhotoCategory;

/**
 * 封装首页概览信息，便于 Fragment 一次性渲染统计数据。
 */
public class HomeUiState {

    private final PhotoCategory selectedCategory;
    private final String searchQuery;
    private final int totalPhotos;
    private final int favoriteCount;
    private final int filteredCount;
    private final String insight;

    public HomeUiState(PhotoCategory selectedCategory,
                       String searchQuery,
                       int totalPhotos,
                       int favoriteCount,
                       int filteredCount,
                       String insight) {
        this.selectedCategory = selectedCategory;
        this.searchQuery = searchQuery;
        this.totalPhotos = totalPhotos;
        this.favoriteCount = favoriteCount;
        this.filteredCount = filteredCount;
        this.insight = insight;
    }

    public PhotoCategory getSelectedCategory() {
        return selectedCategory;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public int getTotalPhotos() {
        return totalPhotos;
    }

    public int getFavoriteCount() {
        return favoriteCount;
    }

    public int getFilteredCount() {
        return filteredCount;
    }

    public String getInsight() {
        return insight;
    }
}

