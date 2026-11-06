package com.goldmonitor;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    private static final String FILE = "gold_monitor";
    public static final long DEFAULT_INTERVAL_MS = 60000L;

    private final SharedPreferences preferences;

    public Prefs(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public long getRefreshInterval() {
        return preferences.getLong("refresh_ms", DEFAULT_INTERVAL_MS);
    }

    public void setRefreshInterval(long value) {
        preferences.edit().putLong("refresh_ms", value).apply();
    }

    public boolean isFlashEnabled() {
        return preferences.getBoolean("flash_enabled", true);
    }

    public void setFlashEnabled(boolean value) {
        preferences.edit().putBoolean("flash_enabled", value).apply();
    }

    public float getFlashThreshold() {
        return preferences.getFloat("flash_threshold", 1.0f);
    }

    public void setFlashThreshold(float value) {
        preferences.edit().putFloat("flash_threshold", value).apply();
    }

    public long getLastFetch() {
        return preferences.getLong("last_fetch", 0L);
    }

    public void setLastFetch(long value) {
        preferences.edit().putLong("last_fetch", value).apply();
    }

    public String getServerDate() {
        return preferences.getString("server_date", null);
    }

    public void setServerDate(String value) {
        preferences.edit().putString("server_date", value).apply();
    }

    public boolean isAlertEnabled(String metal) {
        return preferences.getBoolean("alert_" + metal + "_enabled", false);
    }

    public double getAlertValue(String metal) {
        return parse(preferences.getString("alert_" + metal + "_value", "0"));
    }

    public boolean isAlertAbove(String metal) {
        return preferences.getBoolean("alert_" + metal + "_above", true);
    }

    public boolean isAlertFired(String metal) {
        return preferences.getBoolean("alert_" + metal + "_fired", false);
    }

    public void setAlertFired(String metal, boolean fired) {
        preferences.edit().putBoolean("alert_" + metal + "_fired", fired).apply();
    }

    public void setAlert(String metal, boolean enabled, double value, boolean above) {
        preferences.edit()
                .putBoolean("alert_" + metal + "_enabled", enabled)
                .putString("alert_" + metal + "_value", Double.toString(value))
                .putBoolean("alert_" + metal + "_above", above)
                .putBoolean("alert_" + metal + "_fired", false)
                .apply();
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text);
        } catch (Exception e) {
            return 0d;
        }
    }
}
