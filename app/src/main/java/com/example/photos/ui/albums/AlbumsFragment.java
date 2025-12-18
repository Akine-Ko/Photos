package com.example.photos.ui.albums;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;

import com.example.photos.R;
import com.example.photos.model.PhotoCategory;
import com.example.photos.model.SmartAlbum;
import com.example.photos.ui.common.GridSpacingItemDecoration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class AlbumsFragment extends Fragment {

    private AlbumsAdapter albumsAdapter;
    private TextView emptyTextView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private static final List<String> PRIORITY_ORDER = Arrays.asList(
            "SELFIE",
            "GROUP",
            "QRCODE",
            "CARD",
            "TEXT",
            "NATURE",
            "DRAWING",
            "ARCHITECTURE",
            "PLANTS",
            "FOOD",
            "ELECTRONICS",
            "PETS"
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_albums, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        emptyTextView = view.findViewById(R.id.albumsEmptyTextView);
        swipeRefreshLayout = view.findViewById(R.id.albumsSwipeRefresh);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setColorSchemeResources(R.color.brand_primary);
        }
        setupRecyclerView(view);
        android.content.Context app = requireContext().getApplicationContext();
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> renderAlbumsAsync(app));
            swipeRefreshLayout.setRefreshing(true);
        }
        renderAlbumsAsync(app);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        swipeRefreshLayout = null;
        emptyTextView = null;
    }

    private void setupRecyclerView(@NonNull View view) {
        RecyclerView recyclerView = view.findViewById(R.id.albumsRecyclerView);
        albumsAdapter = new AlbumsAdapter(new AlbumsAdapter.OnAlbumActionListener() {
            @Override
            public void onAlbumClick(SmartAlbum album) {
                android.content.Intent intent = new android.content.Intent(requireContext(), CategoryPhotosActivity.class);
                intent.putExtra(CategoryPhotosActivity.EXTRA_CATEGORY, album.getTitle());
                startActivity(intent);
                requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }

            @Override
            public void onAlbumLongClick(SmartAlbum album) {
                if (!isAdded()) return;
                android.app.Activity activity = getActivity();
                if (activity instanceof com.example.photos.MainActivity) {
                    ((com.example.photos.MainActivity) activity).enterAlbumsSelectionModeAndSelect(album);
                } else {
                    showDeleteAlbumDialog(album);
                }
            }
        });
        final int spanCount = 4;
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        int spacing = getResources().getDimensionPixelSize(R.dimen.home_list_spacing);
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(spanCount, spacing, true));
        recyclerView.setAdapter(albumsAdapter);
    }

    private void renderAlbumsAsync(@NonNull android.content.Context appContext) {
        if (swipeRefreshLayout != null && !swipeRefreshLayout.isRefreshing()) {
            swipeRefreshLayout.setRefreshing(true);
        }
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            List<SmartAlbum> albums = new ArrayList<>();
            boolean success = true;
            try {
                // Normalize legacy WORK -> TEXT once before querying
                try { com.example.photos.db.PhotosDb.get(appContext).categoryDao().renameCategory("WORK", "TEXT"); } catch (Throwable ignore) {}
                try { com.example.photos.db.PhotosDb.get(appContext).categoryDao().renameCategory("IDPHOTO", "CARD"); } catch (Throwable ignore) {}
                com.example.photos.db.CategoryDao dao = com.example.photos.db.PhotosDb.get(appContext).categoryDao();
                List<com.example.photos.db.CategoryDao.CategoryCount> counts = dao.countsByCategory();
                java.util.Set<String> existing = new java.util.HashSet<>();
                List<SmartAlbum> categoryAlbums = new ArrayList<>();
                for (com.example.photos.db.CategoryDao.CategoryCount c : counts) {
                    if (c == null || c.category == null) continue;
                    String cat = c.category.trim();
                    if (cat.isEmpty()) continue;
                    existing.add(cat.toUpperCase());
                    String cover = dao.latestKeyForCategory(cat);
                    if (cover == null) cover = "";
                    categoryAlbums.add(new SmartAlbum(
                            PhotoCategory.ALL,
                            cat,
                            "自动聚类",
                            "本地分类已就绪，点击查看样本",
                            cover,
                            c.cnt
                    ));
                }
                Map<String, Integer> priorityIndex = new HashMap<>();
                for (int i = 0; i < PRIORITY_ORDER.size(); i++) {
                    priorityIndex.put(PRIORITY_ORDER.get(i), i);
                }
                categoryAlbums.sort((a, b) -> {
                    String ta = a.getTitle() == null ? "" : a.getTitle().toUpperCase();
                    String tb = b.getTitle() == null ? "" : b.getTitle().toUpperCase();
                    int ia = priorityIndex.getOrDefault(ta, Integer.MAX_VALUE);
                    int ib = priorityIndex.getOrDefault(tb, Integer.MAX_VALUE);
                    if (ia != ib) return Integer.compare(ia, ib);
                    return ta.compareTo(tb);
                });
                albums.addAll(categoryAlbums);

                List<CustomAlbumsStore.AlbumMeta> custom = CustomAlbumsStore.loadAllWithMeta(appContext);
                if (custom != null) {
                    // Oldest created first
                    custom.sort(Comparator.comparingLong(m -> m.createdAt));
                    for (CustomAlbumsStore.AlbumMeta meta : custom) {
                        if (meta == null || meta.name == null || meta.name.trim().isEmpty()) continue;
                        if (existing.contains(meta.name.trim().toUpperCase())) continue;
                        albums.add(new SmartAlbum(
                                PhotoCategory.ALL,
                                meta.name,
                                "自定义相册",
                                "",
                                "",
                                0
                        ));
                    }
                }
            } catch (Throwable ignore) {
                success = false;
            }
            android.app.Activity activity = getActivity();
            if (activity == null || !isAdded()) return;
            List<SmartAlbum> finalAlbums = new ArrayList<>(albums);
            boolean finalSuccess = success;
            activity.runOnUiThread(() -> {
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
                if (!finalSuccess) {
                    android.widget.Toast.makeText(requireContext(), "刷新失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                }
                if (finalAlbums.isEmpty()) {
                    if (emptyTextView != null) emptyTextView.setVisibility(View.VISIBLE);
                    if (albumsAdapter != null) albumsAdapter.submitList(new ArrayList<>());
                } else {
                    if (emptyTextView != null) emptyTextView.setVisibility(View.GONE);
                    if (albumsAdapter != null) albumsAdapter.submitList(finalAlbums);
                }
            });
        });
    }

    private void showDeleteAlbumDialog(@NonNull SmartAlbum album) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_delete_confirm, null, false);
        TextView message = dialogView.findViewById(R.id.deleteConfirmMessage);
        AppCompatButton positive = dialogView.findViewById(R.id.deleteConfirmPositive);
        AppCompatButton negative = dialogView.findViewById(R.id.deleteConfirmNegative);
        String displayName = CategoryDisplay.displayOf(album.getTitle());
        if (message != null) {
            message.setText(getString(R.string.album_delete_confirm_message, displayName));
        }
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        if (negative != null) {
            negative.setText(R.string.delete_confirm_negative);
            negative.setOnClickListener(v -> dialog.dismiss());
        }
        if (positive != null) {
            positive.setText(R.string.album_delete_confirm_positive);
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                deleteAlbum(album.getTitle(), displayName);
            });
        }
        dialog.show();
    }

    private void deleteAlbum(@NonNull String title, @NonNull String displayName) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                com.example.photos.db.PhotosDb.get(requireContext()).categoryDao().deleteByCategory(title);
                CustomAlbumsStore.remove(requireContext().getApplicationContext(), title);
            } catch (Throwable ignore) {}
            android.app.Activity activity = getActivity();
            if (activity == null || !isAdded()) return;
            activity.runOnUiThread(() -> {
                android.content.Context app = requireContext().getApplicationContext();
                renderAlbumsAsync(app);
                android.widget.Toast.makeText(requireContext(),
                        "已删除相册：" + displayName,
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void promptAddAlbum() {
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(requireContext());
        android.view.View dialogView = inflater.inflate(R.layout.dialog_add_album, null, false);
        android.widget.EditText input = dialogView.findViewById(R.id.addAlbumInput);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        android.view.View confirm = dialogView.findViewById(R.id.addAlbumConfirm);
        android.view.View cancel = dialogView.findViewById(R.id.addAlbumCancel);
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();
            if (name.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), R.string.albums_add_album_empty, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            boolean added = CustomAlbumsStore.add(requireContext().getApplicationContext(), name);
            if (!added) {
                android.widget.Toast.makeText(requireContext(), R.string.albums_add_album_exists, android.widget.Toast.LENGTH_SHORT).show();
            } else {
                android.widget.Toast.makeText(requireContext(), R.string.albums_add_album_done, android.widget.Toast.LENGTH_SHORT).show();
                renderAlbumsAsync(requireContext().getApplicationContext());
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    public void onAddAlbumAction() {
        if (!isAdded()) return;
        promptAddAlbum();
    }

    public void onMultiSelectAction() {
        if (!isAdded()) return;
        setMultiSelectEnabled(true);
    }

    public void setMultiSelectEnabled(boolean enabled) {
        if (albumsAdapter == null) return;
        albumsAdapter.setSelectionMode(enabled);
    }

    public boolean isMultiSelectEnabled() {
        return albumsAdapter != null && albumsAdapter.isSelectionMode();
    }

    public void toggleSelectAll() {
        if (albumsAdapter == null) return;
        albumsAdapter.toggleSelectAll();
    }

    @NonNull
    public List<SmartAlbum> getSelectedAlbums() {
        if (albumsAdapter == null) return new ArrayList<>();
        return albumsAdapter.getSelectedAlbums();
    }

    public void reload() {
        if (!isAdded()) return;
        renderAlbumsAsync(requireContext().getApplicationContext());
    }

    public void selectAlbum(@NonNull SmartAlbum album) {
        if (albumsAdapter == null) return;
        albumsAdapter.selectAlbum(album);
    }
}
