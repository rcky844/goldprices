package com.goldmonitor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Format {

    private Format() {
    }

    public static String price(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "\u2014";
        }
        return "$" + String.format(Locale.US, "%,.2f", Double.valueOf(value));
    }

    public static String signed(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return (value >= 0 ? "+" : "") + String.format(Locale.US, "%,.2f", Double.valueOf(value));
    }

    public static String percent(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return (value >= 0 ? "+" : "") + String.format(Locale.US, "%.2f", Double.valueOf(value)) + "%";
    }

    public static String plain(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return String.format(Locale.US, "%.2f", Double.valueOf(value));
    }

    public static String time(long millis) {
        if (millis <= 0) {
            return "";
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }
}
