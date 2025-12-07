package com.example.photos;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.photos.sync.MediaSyncScheduler;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 顶层容器 Activity：负责挂载导航宿主并串联顶部工具栏与底部导航。
 */
public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private NavController navController;

    private com.example.photos.media.MediaSyncManager syncManager;
    private boolean permissionRequestedOnce = false;

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

        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);

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

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom);
            return insets;
        });
        NavigationUI.setupWithNavController(bottomNavigationView, navController);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        ViewCompat.requestApplyInsets(root);
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
