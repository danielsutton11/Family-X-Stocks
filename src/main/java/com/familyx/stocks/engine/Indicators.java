package com.familyx.stocks.engine;

import java.util.List;

/** Indicator maths over the most recent bars of a series. All methods read closes unless named otherwise. */
public final class Indicators {
    private Indicators() {
    }

    public static double sma(List<Bar> bars, int period) {
        int n = bars.size();
        double sum = 0;
        for (int i = n - period; i < n; i++) {
            sum += bars.get(i).close();
        }
        return sum / period;
    }

    public static double stdev(List<Bar> bars, int period, double mean) {
        int n = bars.size();
        double sq = 0;
        for (int i = n - period; i < n; i++) {
            double d = bars.get(i).close() - mean;
            sq += d * d;
        }
        return Math.sqrt(sq / period);
    }

    /** Z-score of the last close against the trailing {@code period} closes. NaN when the series is flat. */
    public static double zScore(List<Bar> bars, int period) {
        double mean = sma(bars, period);
        double sd = stdev(bars, period, mean);
        if (sd == 0) {
            return Double.NaN;
        }
        return (bars.get(bars.size() - 1).close() - mean) / sd;
    }

    /**
     * EMA of closes ending at index {@code endExclusive - 1}. Seeds from an SMA over a warm-up window of
     * four periods, which is accurate to well under 0.1% and keeps the cost per call bounded.
     */
    public static double ema(List<Bar> bars, int period, int endExclusive) {
        int window = Math.min(endExclusive, period * 4);
        int start = endExclusive - window;
        int seedEnd = start + Math.min(period, window);
        double value = 0;
        for (int i = start; i < seedEnd; i++) {
            value += bars.get(i).close();
        }
        value /= (seedEnd - start);
        double k = 2.0 / (period + 1);
        for (int i = seedEnd; i < endExclusive; i++) {
            value = bars.get(i).close() * k + value * (1 - k);
        }
        return value;
    }

    /** Highest high over the {@code period} bars before the last bar (the last bar is excluded). */
    public static double priorHigh(List<Bar> bars, int period) {
        int n = bars.size();
        double max = Double.NEGATIVE_INFINITY;
        for (int i = n - 1 - period; i < n - 1; i++) {
            max = Math.max(max, bars.get(i).high());
        }
        return max;
    }

    /** Lowest low over the {@code period} bars before the last bar (the last bar is excluded). */
    public static double priorLow(List<Bar> bars, int period) {
        int n = bars.size();
        double min = Double.POSITIVE_INFINITY;
        for (int i = n - 1 - period; i < n - 1; i++) {
            min = Math.min(min, bars.get(i).low());
        }
        return min;
    }
}
