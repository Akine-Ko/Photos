package com.example.photos.ui.albums;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.photos.R;
import com.example.photos.model.PhotoCategory;
import com.example.photos.model.SmartAlbum;
import com.example.photos.ui.common.GridSpacingItemDecoration;

import java.util.ArrayList;
import java.util.List;

public class AlbumsFragment extends Fragment {

    private AlbumsAdapter albumsAdapter;
    private TextView emptyTextView;
    private SwipeRefreshLayout swipeRefreshLayout;

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
                NavController nav = Navigation.findNavController(view);
                Bundle args = new Bundle();
                args.putString(CategoryPhotosFragment.ARG_CATEGORY, album.getTitle());
                nav.navigate(R.id.action_albums_to_categoryPhotos, args);
            }

            @Override
            public void onAlbumLongClick(SmartAlbum album) {
                // 长按删除该相册（分类）
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("删除相册")
                        .setMessage("将移除分类 ‘" + album.getTitle() + "’ 的记录，是否继续？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, which) -> {
                            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                                try {
                                    com.example.photos.db.PhotosDb.get(requireContext()).categoryDao().deleteByCategory(album.getTitle());
                                } catch (Throwable ignore) {}
                                android.app.Activity activity = getActivity();
                                if (activity == null || !isAdded()) return;
                                activity.runOnUiThread(() -> {
                                    android.content.Context app = requireContext().getApplicationContext();
                                    renderAlbumsAsync(app);
                                    android.widget.Toast.makeText(requireContext(), "已删除相册：" + album.getTitle(), android.widget.Toast.LENGTH_SHORT).show();
                                });
                            });
                        })
                        .show();
            }
        });
        final int spanCount = 2;
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
                com.example.photos.db.CategoryDao dao = com.example.photos.db.PhotosDb.get(appContext).categoryDao();
                List<com.example.photos.db.CategoryDao.CategoryCount> counts = dao.countsByCategory();
                for (com.example.photos.db.CategoryDao.CategoryCount c : counts) {
                    if (c == null || c.category == null) continue;
                    String cover = dao.latestKeyForCategory(c.category);
                    if (cover == null) cover = "";
                    albums.add(new SmartAlbum(
                            PhotoCategory.ALL,
                            c.category,
                            "自动聚类",
                            "本地分类已就绪，点击查看样本",
                            cover,
                            c.cnt
                    ));
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
}
