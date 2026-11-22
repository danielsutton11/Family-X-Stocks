package com.familyx.stocks.engine;

import java.util.List;
import java.util.Optional;

/**
 * Buys when the fast EMA crosses above the slow EMA; sells when it crosses back below or the trailing stop is
 * hit. No fixed target: trends are allowed to run.
 */
public class TrendStrategy implements Strategy {
    private final int fast;
    private final int slow;
    private final double trailPct;
    private final int maxHoldHours;

    public TrendStrategy(int fast, int slow, double trailPct, int maxHoldHours) {
        this.fast = fast;
        this.slow = slow;
        this.trailPct = trailPct;
        this.maxHoldHours = maxHoldHours;
    }

    @Override
    public String id() {
        return "trend-" + fast + "-" + slow;
    }

    @Override
    public String displayName() {
        return "Trend following";
    }

    @Override
    public int warmup() {
        return slow + 1;
    }

    @Override
    public Optional<Entry> entry(List<Bar> bars) {
        int n = bars.size();
        if (n < warmup()) {
            return Optional.empty();
        }
        double fastNow = Indicators.ema(bars, fast, n);
        double slowNow = Indicators.ema(bars, slow, n);
        double fastPrev = Indicators.ema(bars, fast, n - 1);
        double slowPrev = Indicators.ema(bars, slow, n - 1);
        if (fastPrev <= slowPrev && fastNow > slowNow) {
            String reason = String.format("%d-hour trend crossed above the %d-hour trend", fast, slow);
            return Optional.of(new Entry(Double.NaN, Double.NaN, trailPct, maxHoldHours, reason));
        }
        return Optional.empty();
    }

    @Override
    public boolean exitOnClose(OpenTrade trade, List<Bar> bars) {
        int n = bars.size();
        if (n < warmup()) {
            return false;
        }
        return Indicators.ema(bars, fast, n) < Indicators.ema(bars, slow, n);
    }
}
