package com.goldmonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class Notifications {

    private static final String TAG = "GoldMonitor";
    private static final int ID_GOLD = 1001;
    private static final int ID_SILVER = 1002;

    private Notifications() {
    }

    static void priceAlert(Context context, String label, double price, double target,
            boolean above) {
        String title = label + " price alert";
        String text = label + " is " + Format.price(price) + "/oz ("
                + (above ? "at or above " : "at or below ") + Format.price(target) + ")";
        int id = "Gold".equals(label) ? ID_GOLD : ID_SILVER;
        post(context, id, title, text);
    }

    private static void post(Context context, int id, String title, String text) {
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent contentIntent = PendingIntent.getActivity(context, id,
                    new Intent(context, MainActivity.class), flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = Notifications26.newBuilder(context);
            } else {
                builder = new Notification.Builder(context);
            }
            builder.setSmallIcon(R.drawable.ic_stat_gold)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_SOUND)
                    .setWhen(System.currentTimeMillis());

            Notification notification = Build.VERSION.SDK_INT >= 16
                    ? Notifications16.build(builder) : builder.getNotification();

            NotificationManager manager = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            manager.notify(id, notification);
        } catch (Exception e) {
            Log.w(TAG, "Could not post price alert: " + e);
        }
    }
}
