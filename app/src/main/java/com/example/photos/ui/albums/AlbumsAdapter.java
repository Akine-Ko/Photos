package com.example.photos.ui.albums;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.photos.R;
import com.example.photos.model.SmartAlbum;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能分类列表 Adapter：绑定标题、摘要和封面图。
 */
class AlbumsAdapter extends RecyclerView.Adapter<AlbumsAdapter.AlbumViewHolder> {

    /**
     * 点击卡片后可进一步跳转或展开详情。
     */
    interface OnAlbumActionListener {
        void onAlbumClick(SmartAlbum album);
        void onAlbumLongClick(SmartAlbum album);
    }

    private final List<SmartAlbum> items = new ArrayList<>();
    private final OnAlbumActionListener actionListener;
    private final Set<String> selectedKeys = new HashSet<>();
    private boolean selectionMode = false;

    AlbumsAdapter(OnAlbumActionListener listener) {
        this.actionListener = listener;
    }

    /**
     * 更新可见的智能分类列表。
     */
    void submitList(List<SmartAlbum> albums) {
        items.clear();
        if (albums != null) {
            items.addAll(albums);
        }
        if (!selectionMode) {
            selectedKeys.clear();
        }
        notifyDataSetChanged();
    }

    void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) {
            selectedKeys.clear();
        }
        notifyDataSetChanged();
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    void toggleSelectAll() {
        if (!selectionMode) return;
        if (items.isEmpty()) return;
        if (selectedKeys.size() >= items.size()) {
            selectedKeys.clear();
            notifyDataSetChanged();
            return;
        }
        selectedKeys.clear();
        for (SmartAlbum album : items) {
            if (album == null) continue;
            String key = keyOf(album);
            if (!key.isEmpty()) selectedKeys.add(key);
        }
        notifyDataSetChanged();
    }

    @NonNull
    List<SmartAlbum> getSelectedAlbums() {
        List<SmartAlbum> out = new ArrayList<>();
        if (selectedKeys.isEmpty()) return out;
        for (SmartAlbum album : items) {
            if (album == null) continue;
            if (selectedKeys.contains(keyOf(album))) {
                out.add(album);
            }
        }
        return out;
    }

    void selectAlbum(@NonNull SmartAlbum album) {
        if (!selectionMode) return;
        String key = keyOf(album);
        if (key.isEmpty()) return;
        if (selectedKeys.add(key)) {
            int pos = findPositionByKey(key);
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            } else {
                notifyDataSetChanged();
            }
        }
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_album, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * 专辑卡片视图，包含封面、数量徽标以及 AI 摘要。
     */
    class AlbumViewHolder extends RecyclerView.ViewHolder {

        private final ShapeableImageView coverImageView;
        private final TextView titleTextView;
        private final TextView countTextView;
        private final View selectedScrim;
        private final ImageView selectionBox;

        AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImageView = itemView.findViewById(R.id.albumCoverImageView);
            titleTextView = itemView.findViewById(R.id.albumTitleTextView);
            countTextView = itemView.findViewById(R.id.albumCountTextView);
            selectedScrim = itemView.findViewById(R.id.albumSelectedScrim);
            selectionBox = itemView.findViewById(R.id.albumSelectionBox);
        }

        void bind(SmartAlbum album) {
            // 封面使用 Glide 加载远程图片，文本区展示统计与摘要。

            // Show Chinese display name for known categories, keep raw key for navigation
            titleTextView.setText(CategoryDisplay.displayOf(album.getTitle()));
            String countText = itemView.getContext().getString(R.string.album_count_template, album.getAssetCount());
            countTextView.setText(countText);
            Glide.with(coverImageView.getContext())
                    .load(album.getCoverUrl())
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .centerCrop()
                    .into(coverImageView);
            boolean selected = selectionMode && selectedKeys.contains(keyOf(album));
            if (selectedScrim != null) selectedScrim.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selectionBox != null) {
                selectionBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
                selectionBox.setSelected(selected);
            }
            itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelected(album);
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos);
                    } else {
                        notifyDataSetChanged();
                    }
                    return;
                }
                if (actionListener != null) actionListener.onAlbumClick(album);
            });
            itemView.setOnLongClickListener(v -> {
                if (selectionMode) {
                    toggleSelected(album);
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos);
                    } else {
                        notifyDataSetChanged();
                    }
                    return true;
                }
                if (actionListener != null) actionListener.onAlbumLongClick(album);
                return true;
            });
        }
    }

    private String keyOf(@NonNull SmartAlbum album) {
        String title = album.getTitle();
        return title == null ? "" : title;
    }

    private boolean toggleSelected(@NonNull SmartAlbum album) {
        String key = keyOf(album);
        if (key.isEmpty()) return false;
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key);
            return false;
        }
        selectedKeys.add(key);
        return true;
    }

    private int findPositionByKey(@NonNull String key) {
        for (int i = 0; i < items.size(); i++) {
            SmartAlbum album = items.get(i);
            if (album == null) continue;
            String k = keyOf(album);
            if (key.equals(k)) return i;
        }
        return RecyclerView.NO_POSITION;
    }
}



