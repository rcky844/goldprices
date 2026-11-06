package com.goldmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * Notification channel support. This class is only ever touched on API 26+, so Android 4.x
 * never has to resolve the NotificationChannel type.
 */
final class Notifications26 {

    private static final String CHANNEL_ID = "price_alerts";

    private Notifications26() {
    }

    static Notification.Builder newBuilder(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.notification_channel_description));
            manager.createNotificationChannel(channel);
        }
        return new Notification.Builder(context, CHANNEL_ID);
    }
}
