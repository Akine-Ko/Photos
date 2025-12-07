package com.example.photos.ui.home;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.example.photos.R;
import com.example.photos.model.Photo;
import com.google.android.material.chip.Chip;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首页卡片 Adapter：负责绑定图片、标签与 AI 洞察文案。
 */
public class PhotoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;
    private static final int TYPE_OVERVIEW = 2;

    /**
     * 对外暴露点击事件，方便 Fragment 触发导航或弹窗。
     */
    public interface OnPhotoClickListener {
        void onPhotoClick(Photo photo);
    }

    private final List<Photo> items = new ArrayList<>();
    private final List<TimelineEntry> timelineItems = new ArrayList<>();
    private final OnPhotoClickListener clickListener;
    private final boolean timelineMode;
    private List<Photo> currentTimelinePhotos = Collections.emptyList();
    private HomeOverview overview;
    private final Executor diffExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger diffGeneration = new AtomicInteger(0);

    private static final int TARGET_WIDTH = 360;
    private static final int TARGET_HEIGHT = 480;

    private static final RequestOptions GLIDE_OPTIONS = new RequestOptions()
            .centerCrop()
            .override(TARGET_WIDTH, TARGET_HEIGHT)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .dontTransform();

    public PhotoAdapter(OnPhotoClickListener clickListener) {
        this(clickListener, false);
    }

    public PhotoAdapter(OnPhotoClickListener clickListener, boolean timelineMode) {
        this.clickListener = clickListener;
        this.timelineMode = timelineMode;
        setHasStableIds(true);
    }

    public boolean isFullWidthPosition(int position) {
        if (!timelineMode || position < 0 || position >= getItemCount()) {
            return false;
        }
        int type = timelineItems.get(position).type;
        return type == TYPE_HEADER || type == TYPE_OVERVIEW;
    }

    public int getItemCountSafe() {
        return timelineMode ? timelineItems.size() : items.size();
    }

    @Nullable
    public String getLabelForPosition(int position) {
        if (!timelineMode || position < 0 || position >= timelineItems.size()) {
            return null;
        }
        TimelineEntry entry = timelineItems.get(position);
        switch (entry.type) {
            case TYPE_HEADER:
                return entry.label;
            case TYPE_PHOTO:
                return extractDayLabel(entry.photo == null ? null : entry.photo.getCaptureDate());
            default:
                return findNearestLabel(position);
        }
    }

    public void setOverview(HomeOverview overview) {
        this.overview = overview;
        if (timelineMode) {
            rebuildTimelineAsync();
        }
    }

    /**
     * 用新的筛选结果刷新展示列表。
     */
    public void submitList(List<Photo> photos) {
        if (!timelineMode) {
            List<Photo> newList = photos == null ? Collections.emptyList() : new ArrayList<>(photos);
            final int runGen = diffGeneration.incrementAndGet();
            final List<Photo> oldList = new ArrayList<>(items);
            diffExecutor.execute(() -> {
                DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new PhotoDiffCallback(oldList, newList));
                mainHandler.post(() -> {
                    if (diffGeneration.get() != runGen) return;
                    items.clear();
                    items.addAll(newList);
                    diff.dispatchUpdatesTo(this);
                });
            });
        } else {
            currentTimelinePhotos = photos == null ? Collections.emptyList() : new ArrayList<>(photos);
            rebuildTimelineAsync();
        }
    }

    private void rebuildTimelineAsync() {
        final List<TimelineEntry> oldEntries = new ArrayList<>(timelineItems);
        final List<TimelineEntry> newEntries = buildTimelineEntries(currentTimelinePhotos);
        final int runGen = diffGeneration.incrementAndGet();
        diffExecutor.execute(() -> {
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new TimelineDiffCallback(oldEntries, newEntries));
            mainHandler.post(() -> {
                if (diffGeneration.get() != runGen) return;
                timelineItems.clear();
                timelineItems.addAll(newEntries);
                diff.dispatchUpdatesTo(this);
            });
        });
    }

    private List<TimelineEntry> buildTimelineEntries(List<Photo> photos) {
        List<TimelineEntry> entries = new ArrayList<>();
        if (overview != null) {
            entries.add(TimelineEntry.overview(overview));
        }
        if (photos == null || photos.isEmpty()) {
            return entries;
        }
        String lastDay = null;
        for (Photo photo : photos) {
            String currentDay = extractDayLabel(photo.getCaptureDate());
            if (!TextUtils.equals(lastDay, currentDay)) {
                entries.add(TimelineEntry.header(currentDay));
                lastDay = currentDay;
            }
            entries.add(TimelineEntry.photo(photo));
        }
        return entries;
    }

    private String extractDayLabel(String dateTime) {
        if (TextUtils.isEmpty(dateTime)) {
            return "\u672a\u6807\u8bb0\u65e5\u671f";
        }
        if (dateTime.length() >= 10) {
            return dateTime.substring(0, 10);
        }
        return dateTime;
    }

    @Nullable
    private String findNearestLabel(int position) {
        for (int i = position - 1; i >= 0; i--) {
            TimelineEntry entry = timelineItems.get(i);
            if (entry.type == TYPE_HEADER) return entry.label;
            if (entry.type == TYPE_PHOTO) {
                return extractDayLabel(entry.photo == null ? null : entry.photo.getCaptureDate());
            }
        }
        for (int i = position + 1; i < timelineItems.size(); i++) {
            TimelineEntry entry = timelineItems.get(i);
            if (entry.type == TYPE_HEADER) return entry.label;
            if (entry.type == TYPE_PHOTO) {
                return extractDayLabel(entry.photo == null ? null : entry.photo.getCaptureDate());
            }
        }
        return null;
    }

    @Nullable
    private Photo findNextPhoto(int startPosition) {
        if (timelineMode) {
            for (int i = startPosition; i < timelineItems.size(); i++) {
                TimelineEntry entry = timelineItems.get(i);
                if (entry.type == TYPE_PHOTO && entry.photo != null) {
                    return entry.photo;
                }
            }
        } else {
            if (startPosition >= 0 && startPosition < items.size()) {
                return items.get(startPosition);
            }
        }
        return null;
    }

    @Override
    public int getItemViewType(int position) {
        if (!timelineMode) return TYPE_PHOTO;
        return timelineItems.get(position).type;
    }

    @Override
    public long getItemId(int position) {
        if (!timelineMode) {
            Photo photo = items.get(position);
            if (photo == null) return RecyclerView.NO_ID;
            if (photo.getId() != null) return photo.getId().hashCode();
            return Objects.hash(photo.getImageUrl(), position);
        }
        return timelineItems.get(position).stableId;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_timeline_header, parent, false);
            return new HeaderViewHolder(view);
        } else if (viewType == TYPE_OVERVIEW) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_overview, parent, false);
            return new OverviewViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(timelineItems.get(position).label);
        } else if (holder instanceof OverviewViewHolder) {
            ((OverviewViewHolder) holder).bind(timelineItems.get(position).overview);
        } else if (holder instanceof PhotoViewHolder) {
            Photo photo = timelineMode ? timelineItems.get(position).photo : items.get(position);
            if (photo != null) {
                ((PhotoViewHolder) holder).bind(photo);
            }
        }
    }

    @Override
    public int getItemCount() {
        return timelineMode ? timelineItems.size() : items.size();
    }

    /**
     * 单个卡片视图，绑定图片与标签。
     */
    class PhotoViewHolder extends RecyclerView.ViewHolder {

        private final ShapeableImageView photoImageView;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            photoImageView = itemView.findViewById(R.id.photoImageView);
        }

        void bind(Photo photo) {
            applyPrivacyAndLoadImage(photo);
            preloadNext(position());

            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onPhotoClick(photo);
                }
            });
        }

        private int position() {
            return getBindingAdapterPosition();
        }

        private void applyPrivacyAndLoadImage(Photo photo) {
            String key = com.example.photos.privacy.PrivacyManager.mediaKeyFrom(null, photo.getImageUrl());
            com.example.photos.privacy.PrivacyPolicy policy = new com.example.photos.privacy.PrivacyManager(itemView.getContext()).getPolicy(key);
            boolean sensitive = policy == com.example.photos.privacy.PrivacyPolicy.SENSITIVE;
            boolean secret = policy == com.example.photos.privacy.PrivacyPolicy.SECRET;

            if (secret) {
                photoImageView.setImageResource(R.drawable.ic_photo_placeholder);
                return;
            }
            Glide.with(photoImageView.getContext())
                    .load(photo.getImageUrl())
                    .apply(GLIDE_OPTIONS)
                    .thumbnail(0.25f)
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_photo_placeholder)
                    .into(photoImageView);
        }

        private void preloadNext(int position) {
            if (position == RecyclerView.NO_POSITION) return;
            preloadAt(position + 1);
            preloadAt(position + 2);
        }

        private void preloadAt(int targetPosition) {
            Photo next = findNextPhoto(targetPosition);
            if (next == null) return;
            String key = com.example.photos.privacy.PrivacyManager.mediaKeyFrom(null, next.getImageUrl());
            com.example.photos.privacy.PrivacyPolicy policy = new com.example.photos.privacy.PrivacyManager(itemView.getContext()).getPolicy(key);
            if (policy == com.example.photos.privacy.PrivacyPolicy.SECRET) return;
            Glide.with(itemView.getContext())
                    .load(next.getImageUrl())
                    .apply(GLIDE_OPTIONS)
                    .thumbnail(0.25f)
                    .preload(TARGET_WIDTH, TARGET_HEIGHT);
        }
    }

    private static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleTextView;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.timelineHeaderTextView);
        }

        void bind(String label) {
            titleTextView.setText(label);
        }
    }

    private static class OverviewViewHolder extends RecyclerView.ViewHolder {
        private final TextView greetingTextView;
        private final TextView insightTextView;
        private final TextView totalValueTextView;
        private final TextView favoriteValueTextView;
        private final TextView filteredValueTextView;

        OverviewViewHolder(@NonNull View itemView) {
            super(itemView);
            greetingTextView = itemView.findViewById(R.id.overviewGreetingTextView);
            insightTextView = itemView.findViewById(R.id.overviewSummaryInsightTextView);
            totalValueTextView = itemView.findViewById(R.id.overviewSummaryTotalValueTextView);
            favoriteValueTextView = itemView.findViewById(R.id.overviewSummaryFavoriteValueTextView);
            filteredValueTextView = itemView.findViewById(R.id.overviewSummaryFilteredValueTextView);
        }

        void bind(HomeOverview overview) {
            if (overview == null) return;
            greetingTextView.setText(overview.greeting);
            insightTextView.setText(overview.insight);
            totalValueTextView.setText(String.valueOf(overview.totalPhotos));
            favoriteValueTextView.setText(String.valueOf(overview.favoriteCount));
            filteredValueTextView.setText(String.valueOf(overview.filteredCount));
        }
    }

    private static class TimelineEntry {
        final int type;
        final String label;
        final Photo photo;
        final HomeOverview overview;
        final long stableId;

        private TimelineEntry(int type, String label, Photo photo, HomeOverview overview, long stableId) {
            this.type = type;
            this.label = label;
            this.photo = photo;
            this.overview = overview;
            this.stableId = stableId;
        }

        static TimelineEntry header(String label) {
            long id = ("header_" + label).hashCode();
            return new TimelineEntry(TYPE_HEADER, label, null, null, id);
        }

        static TimelineEntry photo(Photo photo) {
            long id = photo == null || photo.getId() == null ? RecyclerView.NO_ID : photo.getId().hashCode();
            return new TimelineEntry(TYPE_PHOTO, null, photo, null, id);
        }

        static TimelineEntry overview(HomeOverview overview) {
            long id = overview == null ? 0L : Objects.hash(overview.greeting, overview.totalPhotos, overview.filteredCount);
            return new TimelineEntry(TYPE_OVERVIEW, null, null, overview, id);
        }
    }

    public static class HomeOverview {
        final String greeting;
        final String insight;
        final int totalPhotos;
        final int favoriteCount;
        final int filteredCount;

        public HomeOverview(String greeting, String insight, int totalPhotos, int favoriteCount, int filteredCount) {
            this.greeting = greeting;
            this.insight = insight;
            this.totalPhotos = totalPhotos;
            this.favoriteCount = favoriteCount;
            this.filteredCount = filteredCount;
        }
    }

    private static final class PhotoDiffCallback extends DiffUtil.Callback {
        private final List<Photo> oldList;
        private final List<Photo> newList;

        PhotoDiffCallback(List<Photo> oldList, List<Photo> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList == null ? 0 : oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList == null ? 0 : newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            Photo old = oldList.get(oldItemPosition);
            Photo newer = newList.get(newItemPosition);
            return Objects.equals(old.getId(), newer.getId());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Photo old = oldList.get(oldItemPosition);
            Photo newer = newList.get(newItemPosition);
            return Objects.equals(old.getTitle(), newer.getTitle())
                    && Objects.equals(old.getImageUrl(), newer.getImageUrl())
                    && Objects.equals(old.getCategory(), newer.getCategory())
                    && old.isFavorite() == newer.isFavorite();
        }
    }

    private static final class TimelineDiffCallback extends DiffUtil.Callback {
        private final List<TimelineEntry> oldList;
        private final List<TimelineEntry> newList;

        TimelineDiffCallback(List<TimelineEntry> oldList, List<TimelineEntry> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList == null ? 0 : oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList == null ? 0 : newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            TimelineEntry old = oldList.get(oldItemPosition);
            TimelineEntry newer = newList.get(newItemPosition);
            return old.type == newer.type && old.stableId == newer.stableId;
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            TimelineEntry old = oldList.get(oldItemPosition);
            TimelineEntry newer = newList.get(newItemPosition);
            if (old.type != newer.type) return false;
            switch (old.type) {
                case TYPE_HEADER:
                    return Objects.equals(old.label, newer.label);
                case TYPE_PHOTO:
                    String oldId = old.photo == null ? null : old.photo.getId();
                    String newId = newer.photo == null ? null : newer.photo.getId();
                    return Objects.equals(oldId, newId);
                case TYPE_OVERVIEW:
                    return Objects.equals(old.overview == null ? null : old.overview.insight,
                            newer.overview == null ? null : newer.overview.insight)
                            && Objects.equals(old.overview == null ? null : old.overview.greeting,
                            newer.overview == null ? null : newer.overview.greeting)
                            && (old.overview == null ? -1 : old.overview.totalPhotos)
                            == (newer.overview == null ? -1 : newer.overview.totalPhotos);
                default:
                    return false;
            }
        }
    }
}
