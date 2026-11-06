package com.goldmonitor.data;

/** Everything the UI needs for one rendering pass. */
public final class Snapshot {

    public Spot spot;
    public Double liveGold;
    public Double liveSilver;
    public Performance goldPerformance;
    public Performance silverPerformance;
    public byte[] goldChart;
    public byte[] silverChart;

    /** Wall clock time of the refresh that produced this snapshot. */
    public long fetchedAt;

    /** True when at least one source could not be refreshed and cached data was used. */
    public boolean stale;

    public int failedSources;
    public String firstError;

    public boolean hasData() {
        return spot != null || liveGold != null || liveSilver != null
                || goldPerformance != null || silverPerformance != null
                || (goldChart != null && goldChart.length > 0)
                || (silverChart != null && silverChart.length > 0);
    }
}
