package com.familyx.stocks.engine;

import java.util.List;
import java.util.Optional;

/**
 * Buys when the close breaks above the highest high of the previous {@code entryLookback} hours; sells on a
 * close below the lowest low of the previous {@code exitLookback} hours or on the trailing stop.
 */
public class BreakoutStrategy implements Strategy {
    private final int entryLookback;
    private final int exitLookback;
    private final double trailPct;
    private final int maxHoldHours;

    public BreakoutStrategy(int entryLookback, int exitLookback, double trailPct, int maxHoldHours) {
        this.entryLookback = entryLookback;
        this.exitLookback = exitLookback;
        this.trailPct = trailPct;
        this.maxHoldHours = maxHoldHours;
    }

    @Override
    public String id() {
        return "breakout-" + entryLookback + "-" + exitLookback;
    }

    @Override
    public String displayName() {
        return "Breakout";
    }

    @Override
    public int warmup() {
        return Math.max(entryLookback, exitLookback) + 2;
    }

    @Override
    public Optional<Entry> entry(List<Bar> bars) {
        if (bars.size() < warmup()) {
            return Optional.empty();
        }
        Bar last = bars.get(bars.size() - 1);
        Bar prev = bars.get(bars.size() - 2);
        double high = Indicators.priorHigh(bars, entryLookback);
        // Only fire on the bar that breaks out, not on every bar that stays above the old high.
        double prevHigh = Indicators.priorHigh(bars.subList(0, bars.size() - 1), entryLookback);
        if (last.close() > high && prev.close() <= prevHigh) {
            String reason = String.format("price broke above its %d-hour high of %.2f", entryLookback, high);
            return Optional.of(new Entry(Double.NaN, Double.NaN, trailPct, maxHoldHours, reason));
        }
        return Optional.empty();
    }

    @Override
    public boolean exitOnClose(OpenTrade trade, List<Bar> bars) {
        if (bars.size() < warmup()) {
            return false;
        }
        return bars.get(bars.size() - 1).close() < Indicators.priorLow(bars, exitLookback);
    }
}
