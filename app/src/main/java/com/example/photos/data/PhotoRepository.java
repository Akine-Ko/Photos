package com.example.photos.data;

import com.example.photos.model.Photo;
import com.example.photos.model.PhotoCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地数据仓库：充当离线样例数据源，同时封装筛选统计逻辑。
 */
public class PhotoRepository {

    private static PhotoRepository instance;
    private final List<Photo> cachedPhotos;

    private PhotoRepository() {
        this.cachedPhotos = Collections.unmodifiableList(buildSamplePhotos());
    }

    /**
     * 单例入口：在客户端缓存场景下复用一份数据即可。
     */
    public static synchronized PhotoRepository getInstance() {
        if (instance == null) {
            instance = new PhotoRepository();
        }
        return instance;
    }

    /**
     * 返回全部样例数据，用于首次渲染。
     */
    public List<Photo> getAllPhotos() {
        return new ArrayList<>(cachedPhotos);
    }

    /**
     * 组合类别与关键词过滤，输出按时间倒序的列表。
     */
    public List<Photo> filterPhotos(String query, PhotoCategory category) {
        List<Photo> filtered = new ArrayList<>();
        for (Photo photo : cachedPhotos) {
            if (photo.isInCategory(category) && photo.matchesQuery(query)) {
                filtered.add(photo);
            }
        }
        filtered.sort(Comparator.comparing(Photo::getCaptureDate).reversed());
        return filtered;
    }

    /**
     * 构造各智能分类的数量统计，供“智能分类”页面使用。
     */
    public Map<PhotoCategory, Integer> buildCategoryDistribution() {
        Map<PhotoCategory, Integer> distribution = new LinkedHashMap<>();
        distribution.put(PhotoCategory.ALL, cachedPhotos.size());
        for (PhotoCategory category : PhotoCategory.values()) {
            if (category == PhotoCategory.ALL) {
                continue;
            }
            int count = 0;
            for (Photo photo : cachedPhotos) {
                if (photo.getCategory() == category) {
                    count++;
                }
            }
            distribution.put(category, count);
        }
        return distribution;
    }

    /**
     * 统计收藏数，喂给首页驾驶舱指标。
     */
    public int countFavorites() {
        int total = 0;
        for (Photo photo : cachedPhotos) {
            if (photo.isFavorite()) {
                total++;
            }
        }
        return total;
    }

    /**
     * 首页筛选条可用的分类，按业务优先级排序。
     */
    public List<PhotoCategory> getFilterableCategories() {
        List<PhotoCategory> categories = new ArrayList<>();
        categories.add(PhotoCategory.ALL);
        categories.addAll(Arrays.asList(
                PhotoCategory.SELFIE,
                PhotoCategory.GROUP,
                PhotoCategory.GROUP,
                PhotoCategory.TRAVEL,
                PhotoCategory.FOOD,
                PhotoCategory.TEXT,
                PhotoCategory.QRCODE,
                PhotoCategory.LIFE));
        return categories;
    }

    /**
     * 模拟端侧智能分析后的元数据，涵盖多场景文案。
     */
    private List<Photo> buildSamplePhotos() {
        List<Photo> sample = new ArrayList<>();
        sample.add(new Photo(
                "1",
                "晨练的爸爸",
                "健康档案：清晨心率 62 bpm，建议保持运动节奏。",
                "2025-03-12 06:15",
                "杭州·钱塘江畔",
                Arrays.asList("人物", "健康", "日常"),
                "https://images.unsplash.com/photo-1518611507436-f9221403cca1",
                true,
                PhotoCategory.GROUP
        ));
        sample.add(new Photo(
                "2",
                "川藏线日出",
                "AI 建议生成 4K 壁纸，色彩稳定度 92%。",
                "2024-10-03 05:48",
                "四川·理塘",
                Arrays.asList("旅行", "自然", "精选"),
                "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee",
                true,
                PhotoCategory.TRAVEL
        ));
        sample.add(new Photo(
                "3",
                "团队路演现场",
                "识别到 PPT 文字“季度 OKR”，自动归档至工作专辑。",
                "2024-12-18 14:03",
                "上海·张江",
                Arrays.asList("工作", "会议记录", "文档"),
                "https://images.unsplash.com/photo-1557804506-669a67965ba0",
                false,
                PhotoCategory.TEXT
        ));
        sample.add(new Photo(
                "4",
                "智能料理成果",
                "热量 356 kcal，适合加入今日低脂饮食计划。",
                "2025-01-21 18:22",
                "深圳·后海",
                Arrays.asList("美食", "健康", "记录"),
                "https://images.unsplash.com/photo-1490645935967-10de6ba17061",
                false,
                PhotoCategory.FOOD
        ));
        sample.add(new Photo(
                "5",
                "周末城市漫步",
                "自动识别建筑风格：Art Deco，推荐创建“城市灵感”画册。",
                "2025-02-16 16:35",
                "广州·老城区",
                Arrays.asList("旅行", "建筑", "灵感"),
                "https://images.unsplash.com/photo-1489515217757-5fd1be406fef",
                false,
                PhotoCategory.TRAVEL
        ));
        sample.add(new Photo(
                "6",
                "实验室白板",
                "提取 3 条关键待办，已同步到事项看板。",
                "2025-03-02 11:08",
                "北京·中关村",
                Arrays.asList("工作", "智识", "OCR"),
                "https://images.unsplash.com/photo-1521737604893-d14cc237f11d",
                true,
                PhotoCategory.TEXT
        ));
        sample.add(new Photo(
                "7",
                "家庭观影夜",
                "识别 3 张微笑表情，情绪指数 87 分。",
                "2024-11-09 21:14",
                "南京·江北新区",
                Arrays.asList("生活", "亲情", "瞬间"),
                "https://images.unsplash.com/photo-1524504388940-b1c1722653e1",
                true,
                PhotoCategory.LIFE
        ));
        sample.add(new Photo(
                "8",
                "夜跑配速图",
                "AI 生成训练建议：拉伸 8 分钟，补水 500 ml。",
                "2025-03-05 20:47",
                "苏州·独墅湖",
                Arrays.asList("人物", "健康数据", "运动"),
                "https://images.unsplash.com/photo-1461897104016-0b3b00cc81ee",
                false,
                PhotoCategory.SELFIE
        ));
        sample.add(new Photo(
                "9",
                "航拍稻田",
                "建议加入农业 AI 模型训练集，纹理质量 95%。",
                "2024-09-19 07:25",
                "云南·元阳梯田",
                Arrays.asList("旅行", "科研", "航拍"),
                "https://images.unsplash.com/photo-1500534623283-312aade485b7",
                false,
                PhotoCategory.TRAVEL
        ));
        sample.add(new Photo(
                "10",
                "月度手帐页",
                "OCR 完成 12 条事项，提醒同步到日程表。",
                "2025-03-01 22:09",
                "西安·曲江",
                Arrays.asList("生活", "计划", "文档"),
                "https://images.unsplash.com/photo-1522202176988-66273c2fd55f",
                true,
                PhotoCategory.LIFE
        ));
        sample.add(new Photo(
                "11",
                "付款二维码",
                "检测到二维码，可收藏至“卡证/二维码”相册。",
                "2025-03-08 13:45",
                "上海·徐家汇",
                Arrays.asList("二维码", "卡证", "支付"),
                "https://images.unsplash.com/photo-1504274066651-8d31a536b11a",
                false,
                PhotoCategory.QRCODE
        ));
        return sample;
    }
}








