package com.example.photos.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.photos.R;
import com.example.photos.db.PhotosDb;
import com.example.photos.model.Photo;
import com.example.photos.model.PhotoCategory;
import com.example.photos.sync.MediaIncrementalSyncWorker;
import com.example.photos.ui.common.GridSpacingItemDecoration;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 首页时间线：仅保留瀑布流浏览，搜索迁移到独立页面。
 */
public class HomeFragment extends Fragment {

    private PhotoListViewModel viewModel;
    private PhotoAdapter timelineAdapter;
    private RecyclerView timelineRecyclerView;
    private TimelineFastScroller fastScroller;
    private ActivityResultLauncher<Intent> viewerLauncher;
    private final List<Photo> currentTimelinePhotos = new ArrayList<>();
    private boolean pendingMediaRefresh = false;
    private int lastPhotoCount = 0;
    private boolean pendingScrollToTop = true;
    private String greetingText;
    private final TimelineFastScroller.DateLabelProvider fastScrollLabelProvider =
            new TimelineFastScroller.DateLabelProvider() {
                @Override
                public int getItemCount() {
                    return timelineAdapter == null ? 0 : timelineAdapter.getItemCountSafe();
                }

                @Override
                public String getLabelForPosition(int position) {
                    return timelineAdapter == null ? null : timelineAdapter.getLabelForPosition(position);
                }
            };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        observeIncrementalWorker();
        viewModel = new ViewModelProvider(this).get(PhotoListViewModel.class);
        android.content.Context app = requireContext().getApplicationContext();
        if (com.example.photos.permissions.PermissionsHelper.hasMediaPermission(requireActivity())) {
            observeDbAssets(app);
            pendingScrollToTop = true;
            viewModel.loadFromMediaStore(app);
        }

        greetingText = buildGreetingText();
        setupRecyclerView(view);
        observeViewModel();
        viewerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result == null || result.getData() == null) return;
            ArrayList<String> deletedIds = result.getData().getStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_DELETED_IDS);
            if (deletedIds != null && !deletedIds.isEmpty()) {
                pendingMediaRefresh = true;
                removeDeletedFromMemory(deletedIds);
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (fastScroller != null) {
            fastScroller.detachRecyclerView();
            fastScroller = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (com.example.photos.permissions.PermissionsHelper.hasMediaPermission(requireActivity())) {
            if (pendingMediaRefresh) {
                pendingMediaRefresh = false;
                pendingScrollToTop = true;
            }
            viewModel.reloadFromMediaStore(requireContext().getApplicationContext());
        }
    }

    private void setupRecyclerView(@NonNull View view) {
        timelineRecyclerView = view.findViewById(R.id.homeRecyclerView);
        fastScroller = view.findViewById(R.id.homeFastScroller);
        timelineAdapter = new PhotoAdapter(this::handlePhotoClick, true);
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 4);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return timelineAdapter != null && timelineAdapter.isFullWidthPosition(position)
                        ? layoutManager.getSpanCount() : 1;
            }
        });
        timelineRecyclerView.setLayoutManager(layoutManager);
        int spacing = getResources().getDimensionPixelSize(R.dimen.home_grid_spacing);
        timelineRecyclerView.addItemDecoration(new GridSpacingItemDecoration(layoutManager.getSpanCount(), spacing, true));
        timelineRecyclerView.setAdapter(timelineAdapter);
        timelineRecyclerView.setHasFixedSize(true);
        timelineRecyclerView.setItemViewCacheSize(60);
        timelineRecyclerView.setItemAnimator(null);
        timelineRecyclerView.setVerticalScrollBarEnabled(false);
        if (fastScroller != null) {
            fastScroller.attachRecyclerView(timelineRecyclerView, fastScrollLabelProvider);
            fastScroller.updateVisibility();
        }
    }

    private void observeViewModel() {
        viewModel.getPhotosLiveData().observe(getViewLifecycleOwner(), photos -> {
            boolean scrollToTop = pendingScrollToTop || (lastPhotoCount == 0 && photos != null && !photos.isEmpty());
            pendingScrollToTop = false;
            lastPhotoCount = photos == null ? 0 : photos.size();
            currentTimelinePhotos.clear();
            if (photos != null) currentTimelinePhotos.addAll(photos);
            if (timelineAdapter != null) {
                timelineAdapter.submitList(photos);
            }
            refreshFastScroller();
            if (scrollToTop) {
                scrollTimelineToTop();
            }
        });
        viewModel.getUiStateLiveData().observe(getViewLifecycleOwner(), uiState -> {
            if (uiState == null) return;
            updateOverviewState(uiState);
        });
    }

    private void handlePhotoClick(Photo photo) {
        openInViewer(photo);
    }

    private void updateOverviewState(HomeUiState uiState) {
        // 数字驾驶舱已移除，不再更新概览卡片。
    }

    private String buildGreetingText() {
        Calendar calendar = Calendar.getInstance(Locale.getDefault());
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        String period;
        if (hour < 6) period = getString(R.string.home_greeting_dawn);
        else if (hour < 12) period = getString(R.string.home_greeting_morning);
        else if (hour < 18) period = getString(R.string.home_greeting_afternoon);
        else period = getString(R.string.home_greeting_evening);
        return getString(R.string.home_greeting_template, period);
    }

    private void observeIncrementalWorker() {
        WorkManager wm = WorkManager.getInstance(requireContext());
        wm.getWorkInfosForUniqueWorkLiveData(MediaIncrementalSyncWorker.UNIQUE_PERIODIC_NAME)
                .observe(getViewLifecycleOwner(), infos -> handleIncrementalResult(infos));
        wm.getWorkInfosForUniqueWorkLiveData(MediaIncrementalSyncWorker.UNIQUE_ONETIME_NAME)
                .observe(getViewLifecycleOwner(), infos -> handleIncrementalResult(infos));
    }

    private void observeDbAssets(@NonNull android.content.Context appContext) {
        PhotosDb.get(appContext)
                .photoDao()
                .observeAll()
                .observe(getViewLifecycleOwner(), assets -> viewModel.updateFromDbAssets(assets));
    }

    private void handleIncrementalResult(@Nullable List<WorkInfo> infos) {
        if (infos == null) return;
        for (WorkInfo info : infos) {
            if (info != null && info.getState() == WorkInfo.State.SUCCEEDED) {
                boolean hasDelta = info.getOutputData().getBoolean("hasDelta", false);
                if (hasDelta) {
                    pendingScrollToTop = true;
                    viewModel.reloadFromMediaStore(requireContext());
                    break;
                }
            }
        }
    }

    private void scrollTimelineToTop() {
        if (timelineRecyclerView == null) return;
        timelineRecyclerView.post(() -> {
            if (timelineRecyclerView != null) {
                timelineRecyclerView.scrollToPosition(0);
            }
            if (fastScroller != null) {
                fastScroller.requestSync();
            }
        });
    }

    private void refreshFastScroller() {
        if (fastScroller == null) return;
        boolean showTimeline = timelineAdapter != null && timelineAdapter.getItemCountSafe() > 1;
        if (showTimeline) {
            fastScroller.updateLabelProvider(fastScrollLabelProvider);
            fastScroller.setVisibility(View.VISIBLE);
        } else {
            fastScroller.updateLabelProvider(null);
            fastScroller.setVisibility(View.GONE);
        }
    }

    private void openInViewer(@NonNull Photo clicked) {
        List<Photo> source = currentTimelinePhotos;
        ViewerPayload payload = buildPayload(source, clicked);
        if (payload == null) return;
        Intent intent = new Intent(requireContext(), com.example.photos.ui.albums.AlbumViewerActivity.class);
        intent.putStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_URLS, payload.urls);
        intent.putStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_IDS, payload.ids);
        intent.putStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_DATES, payload.dates);
        intent.putExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_START_INDEX, payload.startIndex);
        if (viewerLauncher != null) {
            viewerLauncher.launch(intent);
        } else {
            startActivity(intent);
        }
    }

    private ViewerPayload buildPayload(List<Photo> source, @NonNull Photo clicked) {
        if (source == null || source.isEmpty()) return null;
        int clickedIndex = 0;
        for (int i = 0; i < source.size(); i++) {
            Photo p = source.get(i);
            if (p != null && clicked.getId() != null && clicked.getId().equals(p.getId())) {
                clickedIndex = i;
                break;
            }
        }
        final int MAX_ITEMS = 200; // cap extras to avoid TransactionTooLargeException
        int start = Math.max(0, clickedIndex - MAX_ITEMS / 2);
        int end = Math.min(source.size(), start + MAX_ITEMS);
        if (end - start < MAX_ITEMS && end == source.size()) {
            start = Math.max(0, end - MAX_ITEMS);
        }
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> dates = new ArrayList<>();
        for (int i = start; i < end; i++) {
            Photo p = source.get(i);
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

    public void setMultiSelectEnabled(boolean enabled) {
        if (timelineAdapter == null) return;
        timelineAdapter.setSelectionMode(enabled);
    }

    public boolean isMultiSelectEnabled() {
        return timelineAdapter != null && timelineAdapter.isSelectionMode();
    }

    public void toggleSelectAll() {
        if (timelineAdapter == null) return;
        timelineAdapter.toggleSelectAll();
    }

    @NonNull
    public List<Photo> getSelectedPhotos() {
        if (timelineAdapter == null) return new ArrayList<>();
        return timelineAdapter.getSelectedPhotos();
    }

    public void reload() {
        if (!isAdded() || viewModel == null) return;
        viewModel.reloadFromMediaStore(requireContext().getApplicationContext());
    }

    private void removeDeletedFromMemory(List<String> deletedIds) {
        boolean changedTimeline = false;
        for (int i = currentTimelinePhotos.size() - 1; i >= 0; i--) {
            Photo p = currentTimelinePhotos.get(i);
            if (p != null && deletedIds.contains(p.getId())) {
                currentTimelinePhotos.remove(i);
                changedTimeline = true;
            }
        }
        if (changedTimeline) {
            timelineAdapter.submitList(new ArrayList<>(currentTimelinePhotos));
        }
    }
}
