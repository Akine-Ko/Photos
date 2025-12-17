package com.example.photos.ui.albums;

import android.os.Bundle;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.photos.R;
import com.example.photos.data.MediaStoreRepository;
import com.example.photos.model.Photo;
import com.example.photos.ui.common.GridSpacingItemDecoration;
import com.example.photos.ui.home.PhotoAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 展示某个分类下的图片列表（基于 categories_sparse + MediaStore 映射）。
 */
public class CategoryPhotosFragment extends Fragment {

    public static final String ARG_CATEGORY = "category";

    private String category;
    private PhotoAdapter adapter;
    private volatile boolean viewDestroyed = false;
    private final List<Photo> currentPhotos = new ArrayList<>();
    private ActivityResultLauncher<Intent> viewerLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_category_photos, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewDestroyed = false;
        Bundle args = getArguments();
        category = args == null ? null : args.getString(ARG_CATEGORY);
        viewerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result == null || result.getData() == null) return;
            ArrayList<String> deletedIds = result.getData().getStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_DELETED_IDS);
            if (deletedIds == null || deletedIds.isEmpty()) return;
            filterDeleted(deletedIds);
        });
        RecyclerView rv = view.findViewById(R.id.categoryRecyclerView);
        adapter = new PhotoAdapter(photo -> {
            openAlbumViewer(photo);
        });
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 4);
        rv.setLayoutManager(layoutManager);
        int spacing = getResources().getDimensionPixelSize(R.dimen.home_grid_spacing);
        rv.addItemDecoration(new GridSpacingItemDecoration(layoutManager.getSpanCount(), spacing, true));
        rv.setItemAnimator(null);
        rv.setAdapter(adapter);
        android.content.Context app = requireContext().getApplicationContext();
        loadDataAsync(app);
    }

    @Override
    public void onDestroyView() {
        viewDestroyed = true;
        super.onDestroyView();
    }

    public boolean toggleMultiSelect() {
        if (adapter == null) return false;
        boolean enabled = !adapter.isSelectionMode();
        adapter.setSelectionMode(enabled);
        return enabled;
    }

    public void reload() {
        if (!isAdded()) return;
        if (adapter != null) {
            adapter.setSelectionMode(false);
        }
        android.content.Context app = requireContext().getApplicationContext();
        loadDataAsync(app);
    }

    private void filterDeleted(List<String> deletedIds) {
        boolean changed = false;
        for (int i = currentPhotos.size() - 1; i >= 0; i--) {
            Photo p = currentPhotos.get(i);
            if (p != null && deletedIds.contains(p.getId())) {
                currentPhotos.remove(i);
                changed = true;
            }
        }
        if (changed && adapter != null) {
            adapter.submitList(new ArrayList<>(currentPhotos));
        }
    }

    private void loadDataAsync(@NonNull android.content.Context appContext) {
        if (category == null) return;
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            List<String> keys = com.example.photos.db.PhotosDb.get(appContext).categoryDao().mediaKeysByCategory(category, 1000, 0);
            List<Long> ids = new ArrayList<>();
            for (String key : keys) {
                try {
                    String last = android.net.Uri.parse(key).getLastPathSegment();
                    ids.add(Long.parseLong(last));
                } catch (Throwable ignore) {}
            }
            if (ids.isEmpty()) {
                android.app.Activity activity = getActivity();
                if (activity == null || !isAdded() || viewDestroyed) return;
                activity.runOnUiThread(() -> {
                    if (!viewDestroyed && adapter != null) {
                        currentPhotos.clear();
                        adapter.submitList(new ArrayList<>());
                    }
                });
                return;
            }

            List<Photo> acc = new ArrayList<>();
            MediaStoreRepository repo = new MediaStoreRepository();
            final int CHUNK = 100;
            for (int i = 0; i < ids.size(); i += CHUNK) {
                int end = Math.min(i + CHUNK, ids.size());
                List<Long> sub = ids.subList(i, end);
                List<com.example.photos.db.PhotoAsset> assets = com.example.photos.media.MediaScanner.queryByIds(appContext, sub);
                for (com.example.photos.db.PhotoAsset a : assets) {
                    Photo p = MediaStoreRepository.toPhoto(a);
                    if (p != null) acc.add(p);
                }
                // 分批回贴，尽早显示首屏，减少白屏时间
                android.app.Activity activity = getActivity();
                if (activity == null || !isAdded() || viewDestroyed) return;
                List<Photo> snapshot = new ArrayList<>(acc);
                activity.runOnUiThread(() -> {
                    if (!viewDestroyed && adapter != null) {
                        currentPhotos.clear();
                        currentPhotos.addAll(snapshot);
                        adapter.submitList(snapshot);
                    }
                });
            }
        });
    }

    public void setMultiSelectEnabled(boolean enabled) {
        if (adapter == null) return;
        adapter.setSelectionMode(enabled);
    }

    public boolean isMultiSelectEnabled() {
        return adapter != null && adapter.isSelectionMode();
    }

    public void toggleSelectAll() {
        if (adapter == null) return;
        adapter.toggleSelectAll();
    }

    @NonNull
    public List<Photo> getSelectedPhotos() {
        if (adapter == null) return new ArrayList<>();
        return adapter.getSelectedPhotos();
    }

    private void openAlbumViewer(@NonNull Photo photo) {
        if (!isAdded()) return;
        ViewerPayload payload = buildPayload(photo);
        if (payload == null) return;
        android.content.Intent intent = new android.content.Intent(requireContext(), com.example.photos.ui.albums.AlbumViewerActivity.class);
        intent.putStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_URLS, payload.urls);
        intent.putStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_IDS, payload.ids);
        intent.putStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_DATES, payload.dates);
        intent.putExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_START_INDEX, Math.max(0, payload.startIndex));
        viewerLauncher.launch(intent);
    }

    private ViewerPayload buildPayload(Photo clicked) {
        if (clicked == null || currentPhotos.isEmpty()) return null;
        int clickedIndex = 0;
        for (int i = 0; i < currentPhotos.size(); i++) {
            Photo p = currentPhotos.get(i);
            if (p != null && clicked.getId() != null && clicked.getId().equals(p.getId())) {
                clickedIndex = i;
                break;
            }
        }
        final int MAX_ITEMS = 200;
        int start = Math.max(0, clickedIndex - MAX_ITEMS / 2);
        int end = Math.min(currentPhotos.size(), start + MAX_ITEMS);
        if (end - start < MAX_ITEMS && end == currentPhotos.size()) {
            start = Math.max(0, end - MAX_ITEMS);
        }
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> dates = new ArrayList<>();
        for (int i = start; i < end; i++) {
            Photo p = currentPhotos.get(i);
            if (p == null || p.getImageUrl() == null) continue;
            urls.add(p.getImageUrl());
            ids.add(p.getId());
            dates.add(p.getCaptureDate());
        }
        if (urls.isEmpty()) return null;
        ViewerPayload payload = new ViewerPayload();
        payload.urls = urls;
        payload.ids = ids;
        payload.dates = dates;
        payload.startIndex = Math.min(clickedIndex - start, urls.size() - 1);
        return payload;
    }

    private static class ViewerPayload {
        ArrayList<String> urls;
        ArrayList<String> ids;
        ArrayList<String> dates;
        int startIndex;
    }
}
