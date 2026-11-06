package com.goldmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.goldmonitor.data.MarketRepository;
import com.goldmonitor.data.Performance;
import com.goldmonitor.data.Snapshot;
import com.goldmonitor.data.Spot;
import com.goldmonitor.http.Http;

public final class MainActivity extends Activity {

    private static final long INTERVAL_10_SECONDS = 10000L;
    private static final long INTERVAL_30_SECONDS = 30000L;
    private static final long INTERVAL_1_MINUTE = 60000L;
    private static final long INTERVAL_5_MINUTES = 300000L;
    private static final long INTERVAL_15_MINUTES = 900000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshTask = new Runnable() {
        @Override
        public void run() {
            refresh(false);
        }
    };

    private Prefs prefs;
    private Snapshot snapshot;
    private boolean resumed;

    private TextView statusText;
    private TextView updatedText;
    private TextView goldPrice;
    private TextView goldChange;
    private TextView goldMeta;
    private TextView silverPrice;
    private TextView silverChange;
    private TextView silverMeta;
    private ImageView goldChart;
    private ImageView silverChart;
    private TextView goldChartError;
    private TextView silverChartError;
    private LinearLayout goldPerformance;
    private LinearLayout silverPerformance;
    private ProgressBar progress;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Http.init(getApplicationContext());
        prefs = new Prefs(this);
        setContentView(R.layout.activity_main);
        bindViews();

        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refresh(true);
            }
        });

        Snapshot cached = MarketRepository.getLatest();
        if (cached == null) {
            cached = MarketRepository.loadCached(this);
        }
        if (cached != null) {
            snapshot = cached;
            render(cached);
        } else {
            statusText.setText(R.string.status_starting);
            updatedText.setText("");
        }

        refresh(false);
    }

    private void bindViews() {
        statusText = (TextView) findViewById(R.id.status_text);
        updatedText = (TextView) findViewById(R.id.updated_text);
        goldPrice = (TextView) findViewById(R.id.gold_price);
        goldChange = (TextView) findViewById(R.id.gold_change);
        goldMeta = (TextView) findViewById(R.id.gold_meta);
        silverPrice = (TextView) findViewById(R.id.silver_price);
        silverChange = (TextView) findViewById(R.id.silver_change);
        silverMeta = (TextView) findViewById(R.id.silver_meta);
        goldChart = (ImageView) findViewById(R.id.gold_chart);
        silverChart = (ImageView) findViewById(R.id.silver_chart);
        goldChartError = (TextView) findViewById(R.id.gold_chart_error);
        silverChartError = (TextView) findViewById(R.id.silver_chart_error);
        goldPerformance = (LinearLayout) findViewById(R.id.gold_performance);
        silverPerformance = (LinearLayout) findViewById(R.id.silver_performance);
        progress = (ProgressBar) findViewById(R.id.progress);
        refreshButton = (Button) findViewById(R.id.btn_refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        long interval = prefs.getRefreshInterval();
        if (interval > 0 && (snapshot == null
                || System.currentTimeMillis() - snapshot.fetchedAt > interval)) {
            refresh(false);
        }
        scheduleAutoRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(autoRefreshTask);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoRefreshTask);
        super.onDestroy();
    }

    private void refresh(boolean manual) {
        if (MarketRepository.isRefreshing()) {
            if (manual) {
                Toast.makeText(this, R.string.update_in_progress, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!isOnline()) {
            if (manual) {
                Toast.makeText(this, R.string.no_network, Toast.LENGTH_SHORT).show();
            }
            if (snapshot == null) {
                Snapshot cached = MarketRepository.loadCached(this);
                if (cached != null) {
                    snapshot = cached;
                    render(cached);
                }
            }
            if (snapshot != null) {
                statusText.setText(R.string.status_offline);
                statusText.setTextColor(getResources().getColor(R.color.warning));
            }
            scheduleAutoRefresh();
            return;
        }

        setBusy(true);
        final Context applicationContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Snapshot result = MarketRepository.refresh(applicationContext);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false);
                        if (result != null && result.hasData()) {
                            snapshot = result;
                            render(result);
                        } else if (snapshot != null) {
                            render(snapshot);
                            Toast.makeText(MainActivity.this, R.string.update_failed,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            statusText.setText(R.string.status_offline);
                            statusText.setTextColor(getResources().getColor(R.color.warning));
                            updatedText.setText("");
                            Toast.makeText(MainActivity.this, R.string.update_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                        scheduleAutoRefresh();
                    }
                });
            }
        }, "gold-monitor-refresh").start();
    }

    private void scheduleAutoRefresh() {
        handler.removeCallbacks(autoRefreshTask);
        long interval = prefs.getRefreshInterval();
        if (resumed && interval > 0) {
            handler.postDelayed(autoRefreshTask, interval);
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!busy);
        refreshButton.setText(busy ? R.string.updating : R.string.refresh);
    }

    private void render(Snapshot data) {
        Spot spot = data.spot;

        if (data.failedSources > 0) {
            statusText.setText(data.hasData() ? R.string.status_cached : R.string.status_offline);
            statusText.setTextColor(getResources().getColor(R.color.warning));
        } else {
            statusText.setText(R.string.status_live);
            statusText.setTextColor(getResources().getColor(R.color.up));
        }

        String updated = null;
        if (spot != null && spot.dateText != null && spot.dateText.length() > 0) {
            updated = spot.dateText;
        } else if (data.fetchedAt > 0) {
            updated = getString(R.string.updated_at, Format.time(data.fetchedAt));
        }
        updatedText.setText(updated == null ? "" : updated);

        double gold = spot != null ? spot.gold
                : (data.liveGold != null ? data.liveGold.doubleValue() : Double.NaN);
        double silver = spot != null ? spot.silver
                : (data.liveSilver != null ? data.liveSilver.doubleValue() : Double.NaN);

        goldPrice.setText(Format.price(gold));
        silverPrice.setText(Format.price(silver));

        if (spot != null && !Double.isNaN(spot.goldChange)) {
            goldChange.setVisibility(View.VISIBLE);
            goldChange.setText(Format.signed(spot.goldChange) + "   " + Format.percent(spot.goldPercent));
            goldChange.setTextColor(getResources().getColor(spot.goldChange >= 0 ? R.color.up : R.color.down));
        } else {
            goldChange.setVisibility(View.GONE);
        }
        if (spot != null && !Double.isNaN(spot.silverChange)) {
            silverChange.setVisibility(View.VISIBLE);
            silverChange.setText(Format.signed(spot.silverChange) + "   " + Format.percent(spot.silverPercent));
            silverChange.setTextColor(getResources().getColor(spot.silverChange >= 0 ? R.color.up : R.color.down));
        } else {
            silverChange.setVisibility(View.GONE);
        }

        goldMeta.setText(buildMeta(spot == null ? Double.NaN : spot.goldClose, data.liveGold));
        silverMeta.setText(buildMeta(spot == null ? Double.NaN : spot.silverClose, data.liveSilver));

        showChart(goldChart, goldChartError, data.goldChart, R.string.gold_chart_unavailable);
        showChart(silverChart, silverChartError, data.silverChart, R.string.silver_chart_unavailable);

        fillPerformance(goldPerformance, data.goldPerformance);
        fillPerformance(silverPerformance, data.silverPerformance);
    }

    private String buildMeta(double previousClose, Double live) {
        StringBuilder text = new StringBuilder();
        if (!Double.isNaN(previousClose)) {
            text.append(getString(R.string.previous_close, Format.price(previousClose)));
        }
        if (live != null) {
            if (text.length() > 0) {
                text.append("    ");
            }
            text.append(getString(R.string.live_price, Format.price(live.doubleValue())));
        }
        return text.toString();
    }

    private void showChart(ImageView view, TextView error, byte[] data, int errorText) {
        Bitmap bitmap = null;
        if (data != null && data.length > 0) {
            try {
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (Exception e) {
                bitmap = null;
            }
        }
        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setVisibility(View.VISIBLE);
            error.setVisibility(View.GONE);
        } else {
            view.setImageBitmap(null);
            view.setVisibility(View.GONE);
            error.setText(errorText);
            error.setVisibility(View.VISIBLE);
        }
    }

    private void fillPerformance(LinearLayout container, Performance performance) {
        container.removeAllViews();
        if (performance == null || performance.rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.unavailable);
            empty.setTextSize(13f);
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            container.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < performance.rows.size(); i++) {
            Performance.Row row = performance.rows.get(i);
            View view = inflater.inflate(R.layout.row_perf, container, false);
            TextView label = (TextView) view.findViewById(R.id.perf_label);
            TextView amount = (TextView) view.findViewById(R.id.perf_amount);
            TextView percent = (TextView) view.findViewById(R.id.perf_percent);
            label.setText(row.label);
            amount.setText(row.amount);
            percent.setText(row.percentage);
            int color = colorForAmount(row.amount);
            amount.setTextColor(color);
            percent.setTextColor(color);
            container.addView(view);
        }
    }

    private int colorForAmount(String amount) {
        if (amount != null) {
            String trimmed = amount.trim();
            if (trimmed.startsWith("-")) {
                return getResources().getColor(R.color.down);
            }
            if (trimmed.startsWith("+")) {
                return getResources().getColor(R.color.up);
            }
        }
        return getResources().getColor(R.color.text_primary);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        long interval = prefs.getRefreshInterval();
        menu.findItem(R.id.menu_int_off).setChecked(interval == 0);
        menu.findItem(R.id.menu_int_10).setChecked(interval == INTERVAL_10_SECONDS);
        menu.findItem(R.id.menu_int_30).setChecked(interval == INTERVAL_30_SECONDS);
        menu.findItem(R.id.menu_int_60).setChecked(interval == INTERVAL_1_MINUTE);
        menu.findItem(R.id.menu_int_300).setChecked(interval == INTERVAL_5_MINUTES);
        menu.findItem(R.id.menu_int_900).setChecked(interval == INTERVAL_15_MINUTES);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_refresh) {
            refresh(true);
            return true;
        }
        if (id == R.id.menu_alerts) {
            showAlertsDialog();
            return true;
        }
        if (id == R.id.menu_about) {
            showAbout();
            return true;
        }
        if (id == R.id.menu_int_10) {
            setInterval(INTERVAL_10_SECONDS, item);
            return true;
        }
        if (id == R.id.menu_int_off) {
            setInterval(0L, item);
            return true;
        }
        if (id == R.id.menu_int_30) {
            setInterval(INTERVAL_30_SECONDS, item);
            return true;
        }
        if (id == R.id.menu_int_60) {
            setInterval(INTERVAL_1_MINUTE, item);
            return true;
        }
        if (id == R.id.menu_int_300) {
            setInterval(INTERVAL_5_MINUTES, item);
            return true;
        }
        if (id == R.id.menu_int_900) {
            setInterval(INTERVAL_15_MINUTES, item);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setInterval(long milliseconds, MenuItem item) {
        prefs.setRefreshInterval(milliseconds);
        item.setChecked(true);
        scheduleAutoRefresh();
        if (milliseconds == 0) {
            Toast.makeText(this, R.string.auto_refresh_off, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.auto_refresh_set,
                    (int) (milliseconds / 1000L)), Toast.LENGTH_SHORT).show();
        }
    }

    private void showAlertsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_alerts, null);
        final CheckBox goldEnabled = (CheckBox) view.findViewById(R.id.alert_gold_enabled);
        final EditText goldValue = (EditText) view.findViewById(R.id.alert_gold_value);
        final Spinner goldDirection = (Spinner) view.findViewById(R.id.alert_gold_direction);
        final CheckBox silverEnabled = (CheckBox) view.findViewById(R.id.alert_silver_enabled);
        final EditText silverValue = (EditText) view.findViewById(R.id.alert_silver_value);
        final Spinner silverDirection = (Spinner) view.findViewById(R.id.alert_silver_direction);

        goldEnabled.setChecked(prefs.isAlertEnabled("gold"));
        if (prefs.getAlertValue("gold") > 0) {
            goldValue.setText(Format.plain(prefs.getAlertValue("gold")));
        }
        goldDirection.setSelection(prefs.isAlertAbove("gold") ? 0 : 1);

        silverEnabled.setChecked(prefs.isAlertEnabled("silver"));
        if (prefs.getAlertValue("silver") > 0) {
            silverValue.setText(Format.plain(prefs.getAlertValue("silver")));
        }
        silverDirection.setSelection(prefs.isAlertAbove("silver") ? 0 : 1);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.alerts_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.save, null);
        builder.setNegativeButton(R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View ignoredView) {
                                double goldTarget = 0d;
                                if (goldEnabled.isChecked()) {
                                    goldTarget = parsePrice(goldValue);
                                    if (goldTarget <= 0d) {
                                        goldValue.setError(getString(R.string.alert_invalid));
                                        return;
                                    }
                                }
                                double silverTarget = 0d;
                                if (silverEnabled.isChecked()) {
                                    silverTarget = parsePrice(silverValue);
                                    if (silverTarget <= 0d) {
                                        silverValue.setError(getString(R.string.alert_invalid));
                                        return;
                                    }
                                }
                                prefs.setAlert("gold", goldEnabled.isChecked(), goldTarget,
                                        goldDirection.getSelectedItemPosition() == 0);
                                prefs.setAlert("silver", silverEnabled.isChecked(), silverTarget,
                                        silverDirection.getSelectedItemPosition() == 0);
                                dialog.dismiss();
                                Toast.makeText(MainActivity.this, R.string.alerts_saved,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        });
        dialog.show();
    }

    private double parsePrice(EditText field) {
        try {
            return Double.parseDouble(field.getText().toString().trim().replace(",", ""));
        } catch (Exception e) {
            return -1d;
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
