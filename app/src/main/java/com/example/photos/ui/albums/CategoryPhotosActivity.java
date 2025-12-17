package com.example.photos.ui.albums;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.photos.R;
import com.example.photos.db.PhotosDb;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class CategoryPhotosActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY = "extra_category";
    private String categoryKey = "";
    private MaterialToolbar toolbar;
    private TextView selectAllView;
    private TextView selectionTitleView;
    private BottomNavigationView bottomNavigation;
    private int bottomNavBasePaddingBottom = 0;
    private boolean selectionMode = false;

    private ActivityResultLauncher<IntentSenderRequest> deleteLauncher;
    private ArrayList<String> pendingDeleteMediaKeys;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_category_photos);

        View root = findViewById(R.id.categoryPhotosRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.categoryPhotosTopAppBar);
        this.toolbar = toolbar;
        selectAllView = findViewById(R.id.categoryPhotosSelectAll);
        selectionTitleView = findViewById(R.id.categoryPhotosSelectionTitle);
        bottomNavigation = findViewById(R.id.categoryPhotosBottomNavigation);
        if (bottomNavigation != null) {
            bottomNavBasePaddingBottom = bottomNavigation.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        bottomNavBasePaddingBottom + systemBars.bottom);
                return insets;
            });
            bottomNavigation.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_share) {
                    shareSelected();
                    return true;
                } else if (id == R.id.action_delete) {
                    requestDeleteSelected();
                    return true;
                } else if (id == R.id.action_add_to) {
                    addSelectedToAlbum();
                    return true;
                }
                return false;
            });
            bottomNavigation.setOnItemReselectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_share) {
                    shareSelected();
                } else if (id == R.id.action_delete) {
                    requestDeleteSelected();
                } else if (id == R.id.action_add_to) {
                    addSelectedToAlbum();
                }
            });
        }
        if (selectAllView != null) {
            selectAllView.setOnClickListener(v -> {
                CategoryPhotosFragment fragment = currentCategoryFragment();
                if (fragment != null) {
                    fragment.toggleSelectAll();
                }
            });
        }

        setSupportActionBar(toolbar);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationIcon(R.drawable.ic_chevron_left);
        toolbar.setNavigationOnClickListener(v -> finish());

        String category = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CATEGORY);
        categoryKey = category == null ? "" : category;
        updateTitle();

        deleteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    boolean ok = result != null && result.getResultCode() == RESULT_OK;
                    if (ok && pendingDeleteMediaKeys != null && !pendingDeleteMediaKeys.isEmpty()) {
                        deleteCategoryRecordsForKeysAsync(new ArrayList<>(pendingDeleteMediaKeys));
                    } else {
                        pendingDeleteMediaKeys = null;
                    }
                });

        if (savedInstanceState == null) {
            CategoryPhotosFragment fragment = new CategoryPhotosFragment();
            Bundle args = new Bundle();
            args.putString(CategoryPhotosFragment.ARG_CATEGORY, categoryKey);
            fragment.setArguments(args);
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.categoryPhotosContainer, fragment)
                    .commit();
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selectionMode) {
                    exitSelectionMode();
                } else {
                    finish();
                }
            }
        });

        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTitle();
    }

    private void updateTitle() {
        if (selectionMode) {
            String title = getString(R.string.select_items);
            if (selectionTitleView != null) {
                selectionTitleView.setText(title);
                selectionTitleView.setVisibility(View.VISIBLE);
            }
            if (toolbar != null) toolbar.setTitle("");
            ActionBar ab = getSupportActionBar();
            if (ab != null) ab.setTitle("");
            return;
        }
        if (selectionTitleView != null) {
            selectionTitleView.setVisibility(View.GONE);
        }
        String displayName = CategoryDisplay.displayOf(categoryKey);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = categoryKey == null ? "" : categoryKey;
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = getString(R.string.title_albums);
        }
        if (toolbar != null) {
            toolbar.setTitle(displayName);
        }
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setTitle(displayName);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.category_photos_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem multi = menu.findItem(R.id.action_multi_select);
        MenuItem clear = menu.findItem(R.id.action_clear_category_records);
        MenuItem cancel = menu.findItem(R.id.action_cancel_selection);
        if (multi != null) multi.setVisible(!selectionMode);
        if (clear != null) clear.setVisible(!selectionMode);
        if (cancel != null) cancel.setVisible(selectionMode);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (selectionMode) {
                exitSelectionMode();
            } else {
                finish();
            }
            return true;
        }
        if (id == R.id.action_multi_select) {
            enterSelectionMode();
            return true;
        }
        if (id == R.id.action_cancel_selection) {
            exitSelectionMode();
            return true;
        }
        if (id == R.id.action_clear_category_records) {
            showClearRecordsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private CategoryPhotosFragment currentCategoryFragment() {
        androidx.fragment.app.Fragment f = getSupportFragmentManager().findFragmentById(R.id.categoryPhotosContainer);
        if (f instanceof CategoryPhotosFragment) {
            return (CategoryPhotosFragment) f;
        }
        return null;
    }

    private void enterSelectionMode() {
        if (selectionMode) return;
        selectionMode = true;
        CategoryPhotosFragment fragment = currentCategoryFragment();
        if (fragment != null) {
            fragment.setMultiSelectEnabled(true);
        }
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(false);
        }
        if (toolbar != null) {
            toolbar.setNavigationIcon(null);
        }
        if (selectAllView != null) {
            selectAllView.setVisibility(View.VISIBLE);
        }
        if (selectionTitleView != null) {
            selectionTitleView.setVisibility(View.VISIBLE);
        }
        showBottomBar(true);
        invalidateOptionsMenu();
        updateTitle();
    }

    private void exitSelectionMode() {
        if (!selectionMode) return;
        selectionMode = false;
        CategoryPhotosFragment fragment = currentCategoryFragment();
        if (fragment != null) {
            fragment.setMultiSelectEnabled(false);
        }
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }
        if (toolbar != null) {
            toolbar.setNavigationIcon(R.drawable.ic_chevron_left);
        }
        if (selectAllView != null) {
            selectAllView.setVisibility(View.GONE);
        }
        if (selectionTitleView != null) {
            selectionTitleView.setVisibility(View.GONE);
        }
        showBottomBar(false);
        invalidateOptionsMenu();
        updateTitle();
    }

    private void showBottomBar(boolean show) {
        if (bottomNavigation == null) return;
        if (show) {
            if (bottomNavigation.getVisibility() != View.VISIBLE) {
                bottomNavigation.setVisibility(View.VISIBLE);
                bottomNavigation.post(() -> {
                    bottomNavigation.setTranslationY(bottomNavigation.getHeight() + 200f);
                    bottomNavigation.animate()
                            .translationY(0f)
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                            .start();
                });
            }
        } else {
            if (bottomNavigation.getVisibility() == View.VISIBLE) {
                bottomNavigation.animate()
                        .translationY(bottomNavigation.getHeight() + 200f)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            bottomNavigation.setVisibility(View.GONE);
                            bottomNavigation.setTranslationY(0f);
                        })
                        .start();
            }
        }
    }

    private void shareSelected() {
        CategoryPhotosFragment fragment = currentCategoryFragment();
        if (fragment == null) return;
        List<com.example.photos.model.Photo> selected = fragment.getSelectedPhotos();
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_selection, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (com.example.photos.model.Photo p : selected) {
            if (p == null || p.getImageUrl() == null) continue;
            try {
                uris.add(Uri.parse(p.getImageUrl()));
            } catch (Throwable ignored) {
            }
        }
        if (uris.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_selection, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("image/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
            exitSelectionMode();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void requestDeleteSelected() {
        CategoryPhotosFragment fragment = currentCategoryFragment();
        if (fragment == null) return;
        List<com.example.photos.model.Photo> selected = fragment.getSelectedPhotos();
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_selection, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        for (com.example.photos.model.Photo p : selected) {
            if (p == null || p.getImageUrl() == null) continue;
            try {
                uris.add(Uri.parse(p.getImageUrl()));
                keys.add(p.getImageUrl());
            } catch (Throwable ignored) {
            }
        }
        if (uris.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_selection, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PendingIntent pi = android.provider.MediaStore.createDeleteRequest(getContentResolver(), uris);
            pendingDeleteMediaKeys = keys;
            deleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
        } catch (Throwable t) {
            pendingDeleteMediaKeys = null;
            android.widget.Toast.makeText(this, R.string.delete_failed, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCategoryRecordsForKeysAsync(@NonNull ArrayList<String> keys) {
        pendingDeleteMediaKeys = null;
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                com.example.photos.db.CategoryDao dao = PhotosDb.get(getApplicationContext()).categoryDao();
                for (String k : keys) {
                    if (k == null || k.isEmpty()) continue;
                    try { dao.deleteByMediaKey(k); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                CategoryPhotosFragment fragment = currentCategoryFragment();
                if (fragment != null) {
                    fragment.reload();
                }
                exitSelectionMode();
            });
        });
    }

    private void addSelectedToAlbum() {
        CategoryPhotosFragment fragment = currentCategoryFragment();
        if (fragment == null) return;
        List<com.example.photos.model.Photo> selected = fragment.getSelectedPhotos();
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_selection, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Reuse viewer's album picker UI.
        showAlbumPickerForSelected(selected);
    }

    private void showAlbumPickerForSelected(@NonNull List<com.example.photos.model.Photo> selected) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            List<AlbumOption> options = loadAlbumOptions();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (options.isEmpty()) {
                    promptCreateAlbumThenAdd(selected);
                } else {
                    showAlbumPickerDialog(selected, options);
                }
            });
        });
    }

    private void showAlbumPickerDialog(@NonNull List<com.example.photos.model.Photo> selected, @NonNull List<AlbumOption> options) {
        android.view.View dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_album_picker, null, false);
        android.widget.ListView listView = dialogView.findViewById(R.id.albumPickerList);
        android.view.View newButton = dialogView.findViewById(R.id.albumPickerNew);
        android.view.View cancelButton = dialogView.findViewById(R.id.albumPickerCancel);
        List<String> displayNames = new ArrayList<>();
        for (AlbumOption o : options) {
            displayNames.add(o.display);
        }
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this, R.layout.item_album_picker, R.id.albumPickerItemText, displayNames) {
            @NonNull
            @Override
            public android.view.View getView(int position, android.view.View convertView, @NonNull android.view.ViewGroup parent) {
                android.view.View v = super.getView(position, convertView, parent);
                android.widget.TextView tv = v.findViewById(R.id.albumPickerItemText);
                if (tv != null) {
                    tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                }
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setHeaderDividersEnabled(false);
        listView.setFooterDividersEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < options.size()) {
                addSelectedToAlbumNameAsync(selected, options.get(position).name);
            }
            dialog.dismiss();
        });
        if (newButton != null) {
            newButton.setOnClickListener(v -> {
                dialog.dismiss();
                promptCreateAlbumThenAdd(selected);
            });
        }
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
    }

    private void promptCreateAlbumThenAdd(@NonNull List<com.example.photos.model.Photo> selected) {
        android.view.View dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_add_album, null, false);
        android.widget.EditText input = dialogView.findViewById(R.id.addAlbumInput);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        android.view.View confirm = dialogView.findViewById(R.id.addAlbumConfirm);
        android.view.View cancel = dialogView.findViewById(R.id.addAlbumCancel);
        if (cancel != null) cancel.setOnClickListener(v -> dialog.dismiss());
        if (confirm != null) {
            confirm.setOnClickListener(v -> {
                String name = input != null && input.getText() != null ? input.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(this, R.string.albums_add_album_empty, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                CustomAlbumsStore.add(getApplicationContext(), name);
                addSelectedToAlbumNameAsync(selected, name);
                dialog.dismiss();
            });
        }
        dialog.show();
    }

    private void addSelectedToAlbumNameAsync(@NonNull List<com.example.photos.model.Photo> selected, @NonNull String albumName) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = false;
            try {
                com.example.photos.db.CategoryDao dao = PhotosDb.get(getApplicationContext()).categoryDao();
                long now = System.currentTimeMillis();
                for (com.example.photos.model.Photo p : selected) {
                    if (p == null || p.getImageUrl() == null) continue;
                    com.example.photos.db.CategoryRecord r = new com.example.photos.db.CategoryRecord();
                    r.mediaKey = p.getImageUrl();
                    r.category = albumName;
                    r.score = 1f;
                    r.updatedAt = now;
                    dao.upsert(r);
                }
                ok = true;
            } catch (Throwable ignored) {
            }
            boolean finalOk = ok;
            runOnUiThread(() -> {
                if (finalOk) {
                    android.widget.Toast.makeText(this,
                            getString(R.string.album_add_success, albumName),
                            android.widget.Toast.LENGTH_SHORT).show();
                    exitSelectionMode();
                } else {
                    android.widget.Toast.makeText(this, R.string.album_add_failed, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @NonNull
    private List<AlbumOption> loadAlbumOptions() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try {
            List<com.example.photos.db.CategoryDao.CategoryCount> counts =
                    PhotosDb.get(getApplicationContext()).categoryDao().countsByCategory();
            for (com.example.photos.db.CategoryDao.CategoryCount c : counts) {
                if (c == null || c.category == null) continue;
                String trimmed = c.category.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        } catch (Throwable ignored) {
        }
        try {
            List<CustomAlbumsStore.AlbumMeta> metas = CustomAlbumsStore.loadAllWithMeta(getApplicationContext());
            for (CustomAlbumsStore.AlbumMeta m : metas) {
                if (m == null || m.name == null) continue;
                String trimmed = m.name.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        } catch (Throwable ignored) {
        }
        List<AlbumOption> out = new ArrayList<>();
        for (String n : names) {
            out.add(new AlbumOption(n, CategoryDisplay.displayOf(n)));
        }
        try {
            out.sort((a, b) -> a.display.compareToIgnoreCase(b.display));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static class AlbumOption {
        final String name;
        final String display;

        AlbumOption(String name, String display) {
            this.name = name;
            this.display = display == null ? name : display;
        }
    }

    private void showClearRecordsDialog() {
        android.view.View dialogView = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_delete_confirm, null, false);
        TextView message = dialogView.findViewById(R.id.deleteConfirmMessage);
        androidx.appcompat.widget.AppCompatButton positive = dialogView.findViewById(R.id.deleteConfirmPositive);
        androidx.appcompat.widget.AppCompatButton negative = dialogView.findViewById(R.id.deleteConfirmNegative);
        String displayName = CategoryDisplay.displayOf(categoryKey);
        if (message != null) {
            message.setText(getString(R.string.album_clear_records_confirm_message, displayName));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        if (negative != null) {
            negative.setText(R.string.delete_confirm_negative);
            negative.setOnClickListener(v -> dialog.dismiss());
        }
        if (positive != null) {
            positive.setText(R.string.ok);
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                clearRecordsAsync();
            });
        }
        dialog.show();
    }

    private void clearRecordsAsync() {
        final String key = categoryKey == null ? "" : categoryKey;
        final String displayName = CategoryDisplay.displayOf(key);
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                PhotosDb.get(getApplicationContext()).categoryDao().deleteByCategory(key);
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                CategoryPhotosFragment fragment = currentCategoryFragment();
                if (fragment != null) {
                    fragment.reload();
                }
                android.widget.Toast.makeText(this,
                        getString(R.string.album_clear_records_done, displayName),
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    public void finish() {
        if (selectionMode) {
            exitSelectionMode();
            return;
        }
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
