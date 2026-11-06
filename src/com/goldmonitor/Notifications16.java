package com.goldmonitor;

import android.app.Notification;

/**
 * Notification.Builder.build() only exists from API 16. Keeping the call in its own class means
 * Dalvik on Android 4.0 never has to resolve it when it verifies {@link Notifications}.
 */
final class Notifications16 {

    private Notifications16() {
    }

    static Notification build(Notification.Builder builder) {
        return builder.build();
    }
}
