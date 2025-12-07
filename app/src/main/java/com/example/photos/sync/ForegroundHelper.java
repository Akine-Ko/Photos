package com.example.photos.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.content.pm.ServiceInfo;

import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;

import com.example.photos.R;

final class ForegroundHelper {
    private static final String CHANNEL_ID = "classification";

    private ForegroundHelper() {}

    static ForegroundInfo create(Context ctx, String title, String text, int notificationId) {
        createChannel(ctx);
        Notification notification = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_nav_search)
                .setOngoing(true)
                .setSilent(true)
                .build();
        int typeMask = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            typeMask = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
            return new ForegroundInfo(notificationId, notification, typeMask);
        } else if (Build.VERSION.SDK_INT >= 29) {
            typeMask = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            return new ForegroundInfo(notificationId, notification, typeMask);
        } else {
            return new ForegroundInfo(notificationId, notification);
        }
    }

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
        if (existing != null) return;
        CharSequence name = ctx.getString(R.string.notification_channel_processing);
        String description = ctx.getString(R.string.notification_channel_processing_desc);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(description);
        nm.createNotificationChannel(ch);
    }
}
