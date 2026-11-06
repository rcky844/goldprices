package com.goldmonitor.data;

import android.content.Context;
import android.util.Log;

import com.goldmonitor.Alerts;
import com.goldmonitor.Prefs;
import com.goldmonitor.http.Http;
import com.goldmonitor.http.HttpResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads, parses and caches all of the data sources. A refresh is allowed to fail partially:
 * every source falls back to its cached copy independently.
 */
public final class MarketRepository {

    private static final String TAG = "GoldMonitor";

    public static final String URL_SPOT = "https://data-asg.goldprice.org/dbXRates/USD";
    public static final String URL_GOLD_LIVE = "https://data-asg.goldprice.org/GetData/USD-XAU/1";
    public static final String URL_SILVER_LIVE = "https://data-asg.goldprice.org/GetData/USD-XAG/1";
    public static final String URL_GOLD_CHART = "https://charts.goldprice.org/charts/gold_1d_o_USD_z.png";
    public static final String URL_SILVER_CHART = "https://charts.goldprice.org/charts/silver_1d_o_USD_z.png";
    public static final String URL_GOLD_PERFORMANCE =
            "https://goldprice.org/performance-json/gold-price-performance-USD.json";
    public static final String URL_SILVER_PERFORMANCE =
            "https://goldprice.org/performance-json/silver-price-performance-USD.json";

    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile Snapshot latest;
    private static volatile long lastChartUpdateMs;

    private MarketRepository() {
    }

    public static Snapshot getLatest() {
        return latest;
    }

    public static boolean isRefreshing() {
        return REFRESHING.get();
    }

    public static Snapshot refresh(Context context) {
        if (!REFRESHING.compareAndSet(false, true)) {
            return latest;
        }
        Snapshot snapshot = new Snapshot();
        snapshot.fetchedAt = System.currentTimeMillis();
        try {
            Fetcher fetcher = new Fetcher(context);

            // Spot data needs special treatment: alerts may only fire on a fresh download.
            Payload spotPayload = fetcher.load("spot.json", URL_SPOT);
            Spot spot = spotPayload == null ? null : parseSpot(spotPayload.bytes);
            boolean spotFresh = spot != null && spotPayload.network;
            if (spot == null) {
                spot = parseSpot(Cache.read(context, "spot.json"));
                if (spot != null) {
                    fetcher.noteFallback("spot prices");
                }
            } else if (spotPayload.network) {
                Cache.write(context, "spot.json", spotPayload.bytes);
            }
            snapshot.spot = spot;

            snapshot.liveGold = loadValue(context, fetcher, "gold-live.json", URL_GOLD_LIVE,
                    QUOTE_PARSER);
            snapshot.liveSilver = loadValue(context, fetcher, "silver-live.json", URL_SILVER_LIVE,
                    QUOTE_PARSER);
            snapshot.goldPerformance = loadValue(context, fetcher, "gold-performance.json",
                    URL_GOLD_PERFORMANCE, PERFORMANCE_PARSER);
            snapshot.silverPerformance = loadValue(context, fetcher, "silver-performance.json",
                    URL_SILVER_PERFORMANCE, PERFORMANCE_PARSER);
            Prefs prefsForCharts = new Prefs(context);
            boolean tenSecondRefresh = prefsForCharts.getRefreshInterval() == 10000L;
            boolean chartsDue = !tenSecondRefresh
                    || System.currentTimeMillis() - lastChartUpdateMs >= 30000L;
            if (chartsDue) {
                lastChartUpdateMs = System.currentTimeMillis();
                snapshot.goldChart = loadValue(context, fetcher, "gold-chart.png", URL_GOLD_CHART,
                        IMAGE_PARSER);
                snapshot.silverChart = loadValue(context, fetcher, "silver-chart.png",
                        URL_SILVER_CHART, IMAGE_PARSER);
            } else {
                snapshot.goldChart = IMAGE_PARSER.parse(Cache.read(context, "gold-chart.png"));
                snapshot.silverChart = IMAGE_PARSER.parse(Cache.read(context, "silver-chart.png"));
            }

            snapshot.failedSources = fetcher.failures;
            snapshot.firstError = fetcher.firstError;
            snapshot.stale = fetcher.failures > 0;

            Prefs prefs = new Prefs(context);
            prefs.setLastFetch(snapshot.fetchedAt);
            if (spot != null && spot.dateText != null) {
                prefs.setServerDate(spot.dateText);
            }

            if (spotFresh) {
                Alerts.check(context, prefs, spot);
            }

            latest = snapshot;
            return snapshot;
        } catch (Exception e) {
            Log.w(TAG, "Refresh failed: " + e);
            if (latest != null) {
                return latest;
            }
            snapshot.stale = true;
            snapshot.failedSources = 7;
            snapshot.firstError = e.toString();
            latest = snapshot;
            return snapshot;
        } finally {
            REFRESHING.set(false);
            Http.closeIdleConnections();
        }
    }

    /** Restores the last successful download from disk without touching the network. */
    public static Snapshot loadCached(Context context) {
        Snapshot snapshot = new Snapshot();
        snapshot.stale = true;
        Prefs prefs = new Prefs(context);
        snapshot.fetchedAt = prefs.getLastFetch();

        snapshot.spot = parseSpot(Cache.read(context, "spot.json"));
        if (snapshot.spot != null && snapshot.spot.dateText == null) {
            snapshot.spot.dateText = prefs.getServerDate();
        }
        snapshot.liveGold = parseQuote(Cache.read(context, "gold-live.json"));
        snapshot.liveSilver = parseQuote(Cache.read(context, "silver-live.json"));
        snapshot.goldPerformance = parsePerformance(Cache.read(context, "gold-performance.json"));
        snapshot.silverPerformance = parsePerformance(Cache.read(context, "silver-performance.json"));
        snapshot.goldChart = Cache.read(context, "gold-chart.png");
        snapshot.silverChart = Cache.read(context, "silver-chart.png");

        return snapshot.hasData() ? snapshot : null;
    }

    private static <T> T loadValue(Context context, Fetcher fetcher, String name, String url,
            Parser<T> parser) {
        Payload payload = fetcher.load(name, url);
        T value = payload == null ? null : parser.parse(payload.bytes);
        if (value == null) {
            byte[] cached = Cache.read(context, name);
            if (cached != null) {
                value = parser.parse(cached);
                if (value != null) {
                    fetcher.noteFallback(name);
                }
            }
        } else if (payload.network) {
            Cache.write(context, name, payload.bytes);
        }
        return value;
    }

    private interface Parser<T> {
        T parse(byte[] data);
    }

    private static final Parser<Double> QUOTE_PARSER = new Parser<Double>() {
        @Override
        public Double parse(byte[] data) {
            return parseQuote(data);
        }
    };

    private static final Parser<Performance> PERFORMANCE_PARSER = new Parser<Performance>() {
        @Override
        public Performance parse(byte[] data) {
            return parsePerformance(data);
        }
    };

    private static final Parser<byte[]> IMAGE_PARSER = new Parser<byte[]>() {
        @Override
        public byte[] parse(byte[] data) {
            if (data == null || data.length < 8) {
                return null;
            }
            // Only accept PNG data so an error page can never overwrite a good chart.
            if ((data[0] & 0xFF) != 0x89 || data[1] != 0x50 || data[2] != 0x4E || data[3] != 0x47) {
                return null;
            }
            return data;
        }
    };

    private static Spot parseSpot(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                return null;
            }
            JSONObject item = items.getJSONObject(0);
            Spot spot = new Spot();
            spot.currency = item.optString("curr", "USD");
            spot.gold = item.optDouble("xauPrice", Double.NaN);
            spot.silver = item.optDouble("xagPrice", Double.NaN);
            spot.goldChange = item.optDouble("chgXau", Double.NaN);
            spot.silverChange = item.optDouble("chgXag", Double.NaN);
            spot.goldPercent = item.optDouble("pcXau", Double.NaN);
            spot.silverPercent = item.optDouble("pcXag", Double.NaN);
            spot.goldClose = item.optDouble("xauClose", Double.NaN);
            spot.silverClose = item.optDouble("xagClose", Double.NaN);
            spot.timestamp = root.optLong("ts", 0L);
            spot.dateText = root.optString("date", null);
            if (Double.isNaN(spot.gold) && Double.isNaN(spot.silver)) {
                return null;
            }
            return spot;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseQuote(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            JSONArray array = new JSONArray(new String(data, "UTF-8"));
            if (array.length() == 0) {
                return null;
            }
            String text = array.getString(0);
            int comma = text.lastIndexOf(',');
            if (comma < 0 || comma == text.length() - 1) {
                return null;
            }
            double value = Double.parseDouble(text.substring(comma + 1).trim());
            if (Double.isNaN(value) || value <= 0) {
                return null;
            }
            return Double.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Performance parsePerformance(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            JSONArray changes = root.optJSONArray("Change");
            if (changes == null || changes.length() == 0) {
                return null;
            }
            Performance performance = new Performance();
            for (int i = 0; i < changes.length(); i++) {
                JSONObject entry = changes.getJSONObject(i);
                java.util.Iterator<String> labels = entry.keys();
                while (labels.hasNext()) {
                    String label = labels.next();
                    JSONObject value = entry.optJSONObject(label);
                    if (value == null) {
                        continue;
                    }
                    performance.rows.add(new Performance.Row(label,
                            value.optString("amount", ""),
                            value.optString("percentage", "")));
                }
            }
            performance.footer = root.optString("footerText", null);
            return performance.rows.isEmpty() ? null : performance;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Payload {
        final byte[] bytes;
        final boolean network;

        Payload(byte[] bytes, boolean network) {
            this.bytes = bytes;
            this.network = network;
        }
    }

    private static final class Fetcher {

        private final Context context;
        private int failures;
        private String firstError;

        Fetcher(Context context) {
            this.context = context;
        }

        Payload load(String name, String url) {
            try {
                HttpResponse response = Http.get(url);
                if (response.status == 200 && response.body.length > 0) {
                    return new Payload(response.body, true);
                }
                noteFailure(name + ": HTTP " + response.status);
            } catch (Exception e) {
                noteFailure(name + ": " + e.getMessage());
            }
            byte[] cached = Cache.read(context, name);
            return cached == null ? null : new Payload(cached, false);
        }

        void noteFallback(String name) {
            failures++;
            Log.w(TAG, "Using cached copy of " + name);
        }

        private void noteFailure(String message) {
            failures++;
            if (firstError == null) {
                firstError = message;
            }
            Log.w(TAG, "Could not refresh " + message);
        }
    }
}
