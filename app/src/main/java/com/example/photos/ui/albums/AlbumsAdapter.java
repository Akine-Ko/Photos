package com.example.photos.ui.albums;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.photos.R;
import com.example.photos.model.SmartAlbum;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;

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
        notifyDataSetChanged();
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

        AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImageView = itemView.findViewById(R.id.albumCoverImageView);
            titleTextView = itemView.findViewById(R.id.albumTitleTextView);
            countTextView = itemView.findViewById(R.id.albumCountTextView);
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
            itemView.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onAlbumClick(album);
            });
            itemView.setOnLongClickListener(v -> {
                if (actionListener != null) actionListener.onAlbumLongClick(album);
                return true;
            });
        }
    }
}





