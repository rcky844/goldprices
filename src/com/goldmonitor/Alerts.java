package com.goldmonitor;

import android.content.Context;
import android.util.Log;

import com.goldmonitor.data.Spot;

/** Checks the user configured price thresholds after a fresh spot quote arrives. */
public final class Alerts {

    private static final String TAG = "GoldMonitor";

    private Alerts() {
    }

    public static void check(Context context, Prefs prefs, Spot spot) {
        try {
            checkOne(context, prefs, "gold", "Gold", spot.gold);
            checkOne(context, prefs, "silver", "Silver", spot.silver);
        } catch (Exception e) {
            Log.w(TAG, "Price alert check failed: " + e);
        }
    }

    private static void checkOne(Context context, Prefs prefs, String metal, String label,
            double price) {
        if (Double.isNaN(price) || price <= 0d) {
            return;
        }
        if (!prefs.isAlertEnabled(metal)) {
            return;
        }
        double target = prefs.getAlertValue(metal);
        if (target <= 0d) {
            return;
        }
        boolean above = prefs.isAlertAbove(metal);
        boolean hit = above ? price >= target : price <= target;
        boolean fired = prefs.isAlertFired(metal);
        if (hit && !fired) {
            prefs.setAlertFired(metal, true);
            Notifications.priceAlert(context, label, price, target, above);
        } else if (!hit && fired) {
            prefs.setAlertFired(metal, false);
        }
    }
}
