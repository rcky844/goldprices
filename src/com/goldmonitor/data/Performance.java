package com.goldmonitor.data;

import java.util.ArrayList;
import java.util.List;

/** Price performance over a set of fixed periods (Today, 30 Days, 1 Year, ...). */
public final class Performance {

    public final List<Row> rows = new ArrayList<Row>();
    public String footer;

    public static final class Row {

        public final String label;
        public final String amount;
        public final String percentage;

        public Row(String label, String amount, String percentage) {
            this.label = label;
            this.amount = amount;
            this.percentage = percentage;
        }
    }
}
