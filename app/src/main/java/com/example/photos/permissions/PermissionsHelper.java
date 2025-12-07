package com.example.photos.permissions;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 媒体权限辅助：兼容 Android 14+ 的“仅选定照片访问”
 */
public class PermissionsHelper {

    public static boolean shouldShowRationale(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            // 只要任一权限需要说明，就返回 true
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_MEDIA_IMAGES)
                    || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        } else if (Build.VERSION.SDK_INT >= 33) {
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    public static boolean ensurePermission(Activity activity) {
        if (hasMediaPermission(activity)) return true;
        requestMediaPermission(activity);
        return false;
    }


    public static final int REQ_READ_MEDIA = 1001;

    private static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            // 同时请求，两者其一获批即可（系统可能授予“仅选定”权限）
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            };
        } else if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        } else {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
    }

    public static boolean hasMediaPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            int img = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES);
            int sel = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            return img == PackageManager.PERMISSION_GRANTED || sel == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    public static void requestMediaPermission(Activity activity) {
        ActivityCompat.requestPermissions(activity, requiredPermissions(), REQ_READ_MEDIA);
    }

    /**
     * 是否处于“仅选定照片访问”（Android 14+）
     */
    public static boolean isSelectedPhotosAccess(Activity activity) {
        if (Build.VERSION.SDK_INT < 34) return false;
        int img = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES);
        int sel = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        return img != PackageManager.PERMISSION_GRANTED && sel == PackageManager.PERMISSION_GRANTED;
    }
}
