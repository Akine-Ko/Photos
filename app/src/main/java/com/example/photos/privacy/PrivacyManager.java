package com.example.photos.privacy;

import android.content.Context;

import com.example.photos.db.PhotosDb;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 简易隐私管理器：封装读写策略与 mediaKey 生成，避免在主线程直接访问数据库。
 */
public class PrivacyManager {

    private static final ConcurrentHashMap<String, PrivacyPolicy> POLICY_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final PhotosDb db;

    public PrivacyManager(Context context) {
        this.db = PhotosDb.get(context.getApplicationContext());
        preloadIfNeeded();
    }

    private void preloadIfNeeded() {
        if (!POLICY_CACHE.isEmpty()) return;
        IO.execute(() -> {
            try {
                List<PrivacyOverride> all = db.privacyDao().getAll();
                for (PrivacyOverride o : all) {
                    if (o != null && o.mediaKey != null) {
                        POLICY_CACHE.put(o.mediaKey, PrivacyPolicy.from(o.policy));
                    }
                }
            } catch (Throwable ignore) { }
        });
    }

    /**
     * 根据 mediaKey 获取策略，使用内存缓存；未命中则返回 PUBLIC，避免卡主线程。
     */
    public PrivacyPolicy getPolicy(String mediaKey) {
        if (mediaKey == null || mediaKey.isEmpty()) return PrivacyPolicy.PUBLIC;
        PrivacyPolicy p = POLICY_CACHE.get(mediaKey);
        return p == null ? PrivacyPolicy.PUBLIC : p;
    }

    /**
     * 设置/覆盖策略：同时更新缓存。
     */
    public void setPolicy(String mediaKey, PrivacyPolicy policy, String encryptedPath) {
        if (mediaKey == null || policy == null) return;
        PrivacyOverride o = new PrivacyOverride();
        o.mediaKey = mediaKey;
        o.policy = policy.getCode();
        o.localEncryptedPath = encryptedPath;
        o.lastUpdated = System.currentTimeMillis();
        IO.execute(() -> {
            try { db.privacyDao().upsert(o); } catch (Throwable ignore) {}
        });
        POLICY_CACHE.put(mediaKey, policy);
    }

    public static String mediaKeyFrom(String contentUri, String httpUrl) {
        if (contentUri != null && !contentUri.isEmpty()) return contentUri;
        if (httpUrl != null && !httpUrl.isEmpty()) return "url:" + httpUrl;
        return null;
    }
}