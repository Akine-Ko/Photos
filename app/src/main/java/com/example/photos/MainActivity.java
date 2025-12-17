package com.example.photos;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.photos.sync.MediaSyncScheduler;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶层容器 Activity：负责挂载导航宿主并串联顶部工具栏与底部导航。
 */
public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private NavController navController;
    private int currentDestinationId = 0;

    private com.example.photos.media.MediaSyncManager syncManager;
    private boolean permissionRequestedOnce = false;
    private boolean notificationRequestedOnce = false;
    private boolean batteryPromptedOnce = false;

    private MaterialToolbar topAppBar;
    private BottomNavigationView bottomNavigationView;
    private BottomNavigationView selectionBottomNavigation;
    private int bottomNavBasePaddingBottom = 0;
    private int selectionBottomNavBasePaddingBottom = 0;
    private TextView selectAllView;
    private TextView selectionTitleView;
    private boolean selectionMode = false;

    private ActivityResultLauncher<IntentSenderRequest> deleteLauncher;
    private ArrayList<String> pendingDeleteMediaKeys;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);
        selectAllView = findViewById(R.id.mainSelectAll);
        selectionTitleView = findViewById(R.id.mainSelectionTitle);
        if (selectAllView != null) {
            selectAllView.setOnClickListener(v -> {
                com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
                if (fragment != null) {
                    fragment.toggleSelectAll();
                }
            });
        }

        // 已授权则确保周期兜底在跑
        if (com.example.photos.permissions.PermissionsHelper.hasMediaPermission(this)) {
            MediaSyncScheduler.ensurePeriodic(this);
        }

        // 权限 + 同步初始化
        if (com.example.photos.permissions.PermissionsHelper.hasMediaPermission(this)) {
            syncManager = new com.example.photos.media.MediaSyncManager(this);
            syncManager.fullScanAsync();
            syncManager.registerObserver();
            MediaSyncScheduler.scheduleOnPermissionGranted(this);
            // 轻量特征稀疏预热（最近 200 张）

        } else if (!permissionRequestedOnce) {
            permissionRequestedOnce = true;
            com.example.photos.permissions.PermissionsHelper.requestMediaPermission(this);
        }

        // 通知权限（Android 13+），用于前台服务通知显示进度
        if (!notificationRequestedOnce
                && !com.example.photos.permissions.PermissionsHelper.hasNotificationPermission(this)) {
            notificationRequestedOnce = true;
            com.example.photos.permissions.PermissionsHelper.requestNotificationPermission(this);
        }

        // 忽略电池优化（Doze），提示用户手动放行
        if (!batteryPromptedOnce
                && !com.example.photos.permissions.PermissionsHelper.isIgnoringBatteryOptimizations(this)) {
            batteryPromptedOnce = true;
            com.example.photos.permissions.PermissionsHelper.requestIgnoreBatteryOptimizations(this);
        }

        // Activity 中只保留一个 NavHost 承载各个 Fragment。
        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) {
            throw new IllegalStateException("NavHostFragment not found");
        }
        navController = navHostFragment.getNavController();

        // 将底部三个 tab 标记为顶级目的地，顶部工具栏不显示返回箭头。
        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_home,
                R.id.navigation_search,
                R.id.navigation_albums,
                R.id.navigation_profile
        ).build();

        bottomNavigationView = findViewById(R.id.bottomNavigation);
        if (bottomNavigationView != null) {
            bottomNavBasePaddingBottom = bottomNavigationView.getPaddingBottom();
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottomNavBasePaddingBottom + systemBars.bottom);
            return insets;
        });
        selectionBottomNavigation = findViewById(R.id.selectionBottomNavigation);
        if (selectionBottomNavigation != null) {
            selectionBottomNavBasePaddingBottom = selectionBottomNavigation.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(selectionBottomNavigation, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        selectionBottomNavBasePaddingBottom + systemBars.bottom);
                return insets;
            });
            selectionBottomNavigation.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_share) {
                    shareSelectedFromHome();
                } else if (id == R.id.action_delete) {
                    requestDeleteSelectedFromHome();
                } else if (id == R.id.action_add_to) {
                    addSelectedToAlbumFromHome();
                }
                clearSelectionBottomNavState();
                return false;
            });
            selectionBottomNavigation.setOnItemReselectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_share) {
                    shareSelectedFromHome();
                } else if (id == R.id.action_delete) {
                    requestDeleteSelectedFromHome();
                } else if (id == R.id.action_add_to) {
                    addSelectedToAlbumFromHome();
                }
                clearSelectionBottomNavState();
            });
            clearSelectionBottomNavState();
        }

        NavigationUI.setupWithNavController(bottomNavigationView, navController);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

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

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            currentDestinationId = destination.getId();
            if (selectionMode && currentDestinationId != R.id.navigation_home) {
                exitHomeSelectionMode();
            }
            invalidateOptionsMenu();

            if (destination.getId() == R.id.navigation_category_photos && arguments != null) {
                String cat = arguments.getString(com.example.photos.ui.albums.CategoryPhotosFragment.ARG_CATEGORY);
                if (cat != null && !cat.trim().isEmpty()) {
                    topAppBar.setTitle(com.example.photos.ui.albums.CategoryDisplay.displayOf(cat));
                    return;
                }
            }
            CharSequence label = destination.getLabel();
            if (!selectionMode) {
                topAppBar.setTitle(label == null ? "" : label);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selectionMode) {
                    exitHomeSelectionMode();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.top_app_bar_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem add = menu.findItem(R.id.action_add_album);
        MenuItem multi = menu.findItem(R.id.action_multi_select);
        MenuItem cancel = menu.findItem(R.id.action_cancel_selection);
        boolean onAlbums = currentDestinationId == R.id.navigation_albums;
        boolean onHome = currentDestinationId == R.id.navigation_home;
        if (add != null) add.setVisible(!selectionMode && onAlbums);
        if (multi != null) multi.setVisible(!selectionMode && (onAlbums || onHome));
        if (cancel != null) {
            cancel.setVisible(selectionMode && onHome);
            if (selectionMode && onHome) {
                tintMenuItemBlue(cancel);
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_album) {
            com.example.photos.ui.albums.AlbumsFragment fragment = currentAlbumsFragment();
            if (fragment != null) {
                fragment.onAddAlbumAction();
                return true;
            }
        } else if (id == R.id.action_multi_select) {
            if (currentDestinationId == R.id.navigation_home) {
                enterHomeSelectionMode();
                return true;
            } else {
                com.example.photos.ui.albums.AlbumsFragment fragment = currentAlbumsFragment();
                if (fragment != null) {
                    fragment.onMultiSelectAction();
                    return true;
                }
            }
        } else if (id == R.id.action_cancel_selection) {
            exitHomeSelectionMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private com.example.photos.ui.albums.AlbumsFragment currentAlbumsFragment() {
        androidx.fragment.app.Fragment host = getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (!(host instanceof NavHostFragment)) return null;
        androidx.fragment.app.Fragment current = ((NavHostFragment) host).getChildFragmentManager().getPrimaryNavigationFragment();
        if (current instanceof com.example.photos.ui.albums.AlbumsFragment) {
            return (com.example.photos.ui.albums.AlbumsFragment) current;
        }
        return null;
    }

    private com.example.photos.ui.home.HomeFragment currentHomeFragment() {
        androidx.fragment.app.Fragment host = getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (!(host instanceof NavHostFragment)) return null;
        androidx.fragment.app.Fragment current = ((NavHostFragment) host).getChildFragmentManager().getPrimaryNavigationFragment();
        if (current instanceof com.example.photos.ui.home.HomeFragment) {
            return (com.example.photos.ui.home.HomeFragment) current;
        }
        return null;
    }

    private void enterHomeSelectionMode() {
        if (selectionMode) return;
        if (currentDestinationId != R.id.navigation_home) return;
        selectionMode = true;
        com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
        if (fragment != null) {
            fragment.setMultiSelectEnabled(true);
        }
        if (topAppBar != null) {
            topAppBar.setTitle("");
        }
        if (selectAllView != null) selectAllView.setVisibility(View.VISIBLE);
        if (selectionTitleView != null) selectionTitleView.setVisibility(View.VISIBLE);
        showSelectionBottomBar(true);
        if (bottomNavigationView != null) bottomNavigationView.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    private void exitHomeSelectionMode() {
        if (!selectionMode) return;
        selectionMode = false;
        com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
        if (fragment != null) {
            fragment.setMultiSelectEnabled(false);
        }
        if (selectAllView != null) selectAllView.setVisibility(View.GONE);
        if (selectionTitleView != null) selectionTitleView.setVisibility(View.GONE);
        showSelectionBottomBar(false);
        if (bottomNavigationView != null) bottomNavigationView.setVisibility(View.VISIBLE);
        if (navController != null && topAppBar != null) {
            CharSequence label = navController.getCurrentDestination() == null ? "" : navController.getCurrentDestination().getLabel();
            topAppBar.setTitle(label == null ? "" : label);
        }
        invalidateOptionsMenu();
    }

    private void showSelectionBottomBar(boolean show) {
        if (selectionBottomNavigation == null) return;
        if (show) {
            if (selectionBottomNavigation.getVisibility() != View.VISIBLE) {
                selectionBottomNavigation.setVisibility(View.VISIBLE);
                clearSelectionBottomNavState();
                selectionBottomNavigation.post(() -> {
                    selectionBottomNavigation.setTranslationY(selectionBottomNavigation.getHeight() + 200f);
                    selectionBottomNavigation.animate()
                            .translationY(0f)
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                            .start();
                });
            }
        } else {
            if (selectionBottomNavigation.getVisibility() == View.VISIBLE) {
                selectionBottomNavigation.animate()
                        .translationY(selectionBottomNavigation.getHeight() + 200f)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            selectionBottomNavigation.setVisibility(View.GONE);
                            selectionBottomNavigation.setTranslationY(0f);
                            clearSelectionBottomNavState();
                        })
                        .start();
            }
        }
    }

    private void clearSelectionBottomNavState() {
        if (selectionBottomNavigation == null) return;
        android.view.Menu menu = selectionBottomNavigation.getMenu();
        try {
            menu.setGroupCheckable(0, false, false);
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < menu.size(); i++) {
            try {
                menu.getItem(i).setCheckable(false);
                menu.getItem(i).setChecked(false);
            } catch (Throwable ignored) {
            }
        }
    }

    private void tintMenuItemBlue(@NonNull MenuItem item) {
        CharSequence title = item.getTitle();
        if (title == null) return;
        int color = ContextCompat.getColor(this, R.color.brand_primary);
        SpannableString s = new SpannableString(title);
        s.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        item.setTitle(s);
    }

    private void shareSelectedFromHome() {
        com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
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
            exitHomeSelectionMode();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void requestDeleteSelectedFromHome() {
        com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
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
                com.example.photos.db.CategoryDao dao = com.example.photos.db.PhotosDb.get(getApplicationContext()).categoryDao();
                for (String k : keys) {
                    if (k == null || k.isEmpty()) continue;
                    try {
                        dao.deleteByMediaKey(k);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
                if (fragment != null) {
                    fragment.reload();
                }
                exitHomeSelectionMode();
            });
        });
    }

    private void addSelectedToAlbumFromHome() {
        com.example.photos.ui.home.HomeFragment fragment = currentHomeFragment();
        if (fragment == null) return;
        List<com.example.photos.model.Photo> selected = fragment.getSelectedPhotos();
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_selection, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
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
            public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(R.id.albumPickerItemText);
                if (tv != null) {
                    tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                }
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setHeaderDividersEnabled(false);
        listView.setFooterDividersEnabled(false);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setView(dialogView).create();
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
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setView(dialogView).create();
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
                com.example.photos.ui.albums.CustomAlbumsStore.add(getApplicationContext(), name);
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
                com.example.photos.db.CategoryDao dao = com.example.photos.db.PhotosDb.get(getApplicationContext()).categoryDao();
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
                    exitHomeSelectionMode();
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
                    com.example.photos.db.PhotosDb.get(getApplicationContext()).categoryDao().countsByCategory();
            for (com.example.photos.db.CategoryDao.CategoryCount c : counts) {
                if (c == null || c.category == null) continue;
                String trimmed = c.category.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        } catch (Throwable ignored) {
        }
        try {
            List<com.example.photos.ui.albums.CustomAlbumsStore.AlbumMeta> metas =
                    com.example.photos.ui.albums.CustomAlbumsStore.loadAllWithMeta(getApplicationContext());
            for (com.example.photos.ui.albums.CustomAlbumsStore.AlbumMeta m : metas) {
                if (m == null || m.name == null) continue;
                String trimmed = m.name.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        } catch (Throwable ignored) {
        }
        List<AlbumOption> out = new ArrayList<>();
        for (String n : names) {
            out.add(new AlbumOption(n, com.example.photos.ui.albums.CategoryDisplay.displayOf(n)));
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

    @Override
    public boolean onSupportNavigateUp() {
        if (navController != null) {
            return NavigationUI.navigateUp(navController, appBarConfiguration)
                    || super.onSupportNavigateUp();
        }
        return super.onSupportNavigateUp();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (syncManager != null && com.example.photos.permissions.PermissionsHelper.hasMediaPermission(this)) {
            syncManager.registerObserver();
        }
    }

    @Override
    protected void onStop() {
        if (syncManager != null) {
            syncManager.unregisterObserver();
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == com.example.photos.permissions.PermissionsHelper.REQ_READ_MEDIA) {
            // 兼容 Android 14+：任一媒体访问权限获批即可（包含仅选定照片）
            if (com.example.photos.permissions.PermissionsHelper.hasMediaPermission(this)) {
                syncManager = new com.example.photos.media.MediaSyncManager(this);
                syncManager.fullScanAsync();
                syncManager.registerObserver();
                MediaSyncScheduler.scheduleOnPermissionGranted(this);

            }
        }
    }
}
