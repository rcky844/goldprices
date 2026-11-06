package com.goldmonitor.data;

/** Spot prices for one troy ounce, as reported by data-asg.goldprice.org. */
public final class Spot {

    public String currency = "USD";
    public double gold = Double.NaN;
    public double silver = Double.NaN;
    public double goldChange = Double.NaN;
    public double silverChange = Double.NaN;
    public double goldPercent = Double.NaN;
    public double silverPercent = Double.NaN;
    public double goldClose = Double.NaN;
    public double silverClose = Double.NaN;
    public long timestamp;
    public String dateText;
}
