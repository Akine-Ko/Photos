package com.example.photos.ui.search;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.photos.R;
import com.example.photos.db.PhotosDb;
import com.example.photos.model.Photo;
import com.example.photos.search.TextSearchEngine;
import com.example.photos.search.ImageSearchEngine;
import com.example.photos.settings.SearchPreferences;
import com.example.photos.ui.common.GridSpacingItemDecoration;
import com.example.photos.ui.home.PhotoAdapter;
import com.example.photos.ui.home.PhotoListViewModel;
import com.example.photos.ui.home.TimelineFastScroller;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 独立的搜索页。
 */
public class SearchFragment extends Fragment {

    private PhotoListViewModel viewModel;
    private PhotoAdapter searchAdapter;
    private TextInputEditText searchEditText;
    private RecyclerView searchRecyclerView;
    private TimelineFastScroller fastScroller;
    private ActivityResultLauncher<Intent> viewerLauncher;
    private ActivityResultLauncher<String> imagePickerLauncher;
    private final List<Photo> currentSearchPhotos = new ArrayList<>();
    private ExecutorService textSearchExecutor;
    private int searchLimit = 4;

    private final TextWatcher searchTextWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (s == null || s.length() == 0) {
                clearSearchResults();
            }
        }
        @Override public void afterTextChanged(Editable s) {}
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ensureSearchExecutor();
        viewModel = new ViewModelProvider(this).get(PhotoListViewModel.class);
        android.content.Context app = requireContext().getApplicationContext();
        if (com.example.photos.permissions.PermissionsHelper.hasMediaPermission(requireActivity())) {
            observeDbAssets(app);
            viewModel.loadFromMediaStore(app);
        }
        setupRecyclerView(view);
        setupSearch(view);
        viewerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result == null || result.getData() == null) return;
            ArrayList<String> deletedIds = result.getData().getStringArrayListExtra(com.example.photos.ui.albums.AlbumViewerActivity.EXTRA_DELETED_IDS);
            if (deletedIds != null && !deletedIds.isEmpty()) {
                removeDeletedFromMemory(deletedIds);
            }
        });
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                performImageSearch(uri);
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (searchEditText != null) {
            searchEditText.removeTextChangedListener(searchTextWatcher);
        }
        if (textSearchExecutor != null && !textSearchExecutor.isShutdown()) {
            textSearchExecutor.shutdownNow();
        }
        if (fastScroller != null) {
            fastScroller.detachRecyclerView();
            fastScroller = null;
        }
        super.onDestroyView();
    }

    private void setupRecyclerView(@NonNull View view) {
        searchRecyclerView = view.findViewById(R.id.searchRecyclerView);
        fastScroller = view.findViewById(R.id.searchFastScroller);
        searchAdapter = new PhotoAdapter(this::handlePhotoClick, false);
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 4);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return searchAdapter != null && searchAdapter.isFullWidthPosition(position)
                        ? layoutManager.getSpanCount() : 1;
            }
        });
        searchRecyclerView.setLayoutManager(layoutManager);
        int spacing = getResources().getDimensionPixelSize(R.dimen.home_grid_spacing);
        searchRecyclerView.addItemDecoration(new GridSpacingItemDecoration(layoutManager.getSpanCount(), spacing, true));
        searchRecyclerView.setAdapter(searchAdapter);
        searchRecyclerView.setHasFixedSize(true);
        searchRecyclerView.setItemViewCacheSize(60);
        searchRecyclerView.setItemAnimator(null);
        searchRecyclerView.setVerticalScrollBarEnabled(false);
        if (fastScroller != null) {
            fastScroller.attachRecyclerView(searchRecyclerView, new TimelineFastScroller.DateLabelProvider() {
                @Override
                public int getItemCount() {
                    return searchAdapter == null ? 0 : searchAdapter.getItemCountSafe();
                }

                @Override
                public String getLabelForPosition(int position) {
                    return searchAdapter == null ? null : searchAdapter.getLabelForPosition(position);
                }
            });
            fastScroller.updateVisibility();
        }
    }

    private void setupSearch(@NonNull View view) {
        searchEditText = view.findViewById(R.id.searchEditText);
        searchLimit = SearchPreferences.getSearchLimit(requireContext());
        searchEditText.addTextChangedListener(searchTextWatcher);
        searchEditText.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL || (keyEvent != null && keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performTextSearch(textView.getText() == null ? "" : textView.getText().toString());
                hideKeyboard(textView);
                return true;
            }
            return false;
        });
        MaterialButton imageSearchButton = view.findViewById(R.id.searchImageButton);
        if (imageSearchButton != null) {
            imageSearchButton.setOnClickListener(v -> {
                if (imagePickerLauncher != null) {
                    imagePickerLauncher.launch("image/*");
                }
            });
        }
    }

    private void performTextSearch(String query) {
        ensureSearchExecutor();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            clearSearchResults();
            return;
        }
        searchLimit = SearchPreferences.getSearchLimit(requireContext());
        final int limit = Math.max(1, searchLimit);
        Toast.makeText(requireContext(), R.string.home_search_running, Toast.LENGTH_SHORT).show();
        textSearchExecutor.execute(() -> {
            List<TextSearchEngine.SearchResult> results = TextSearchEngine.search(requireContext(), trimmed, limit);
            List<Photo> photos = new ArrayList<>();
            for (TextSearchEngine.SearchResult result : results) {
                photos.add(result.photo);
            }
            requireActivity().runOnUiThread(() -> applyTextSearchResults(trimmed, photos));
        });
    }

    private void performImageSearch(@NonNull Uri uri) {
        ensureSearchExecutor();
        searchLimit = SearchPreferences.getSearchLimit(requireContext());
        final int limit = Math.max(1, searchLimit);
        Toast.makeText(requireContext(), R.string.home_image_search_running, Toast.LENGTH_SHORT).show();
        textSearchExecutor.execute(() -> {
            List<ImageSearchEngine.SearchResult> results = ImageSearchEngine.search(requireContext(), uri, limit);
            List<Photo> photos = new ArrayList<>();
            for (ImageSearchEngine.SearchResult result : results) {
                photos.add(result.photo);
            }
            requireActivity().runOnUiThread(() -> applyImageSearchResults(photos));
        });
    }

    private void applyTextSearchResults(String query, List<Photo> photos) {
        currentSearchPhotos.clear();
        if (photos != null) currentSearchPhotos.addAll(photos);
        searchAdapter.submitList(photos);
        if (fastScroller != null) {
            fastScroller.updateVisibility();
        }
        String message = getString(R.string.home_search_result_message, photos.size(), query);
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void applyImageSearchResults(List<Photo> photos) {
        currentSearchPhotos.clear();
        if (photos != null) currentSearchPhotos.addAll(photos);
        searchAdapter.submitList(photos);
        if (fastScroller != null) {
            fastScroller.updateVisibility();
        }
        String message = getString(R.string.home_image_search_result_message, photos == null ? 0 : photos.size());
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void clearSearchResults() {
        currentSearchPhotos.clear();
        if (searchAdapter != null) {
            searchAdapter.submitList(new ArrayList<>());
        }
        if (fastScroller != null) {
            fastScroller.updateVisibility();
        }
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    private void ensureSearchExecutor() {
        if (textSearchExecutor == null || textSearchExecutor.isShutdown() || textSearchExecutor.isTerminated()) {
            textSearchExecutor = Executors.newSingleThreadExecutor();
        }
    }

    private void handlePhotoClick(Photo photo) {
        openInViewer(photo);
    }

    private void openInViewer(@NonNull Photo clicked) {
        ViewerPayload payload = buildPayload(currentSearchPhotos, clicked);
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

    private void removeDeletedFromMemory(List<String> deletedIds) {
        boolean changed = false;
        for (int i = currentSearchPhotos.size() - 1; i >= 0; i--) {
            Photo p = currentSearchPhotos.get(i);
            if (p != null && deletedIds.contains(p.getId())) {
                currentSearchPhotos.remove(i);
                changed = true;
            }
        }
        if (changed && searchAdapter != null) {
            searchAdapter.submitList(new ArrayList<>(currentSearchPhotos));
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
        final int MAX_ITEMS = 200;
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

    private void observeDbAssets(@NonNull android.content.Context appContext) {
        PhotosDb.get(appContext)
                .photoDao()
                .observeAll()
                .observe(getViewLifecycleOwner(), assets -> viewModel.updateFromDbAssets(assets));
    }
}
