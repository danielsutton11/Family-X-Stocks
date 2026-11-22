package com.familyx.stocks.engine;

/** A position being held, shared by the backtester and the live model portfolio. */
public class OpenTrade {
    private final double entryPrice;
    private final long entryTs;
    private final double target;
    private final double stop;
    private final double trailPct;
    private final long maxHoldUntil;
    private double highWater;

    public OpenTrade(double entryPrice, long entryTs, double target, double stop, double trailPct,
                     long maxHoldUntil, double highWater) {
        this.entryPrice = entryPrice;
        this.entryTs = entryTs;
        this.target = target;
        this.stop = stop;
        this.trailPct = trailPct;
        this.maxHoldUntil = maxHoldUntil;
        this.highWater = highWater;
    }

    public static OpenTrade open(Entry e, double price, long ts) {
        return new OpenTrade(price, ts, e.target(), e.stop(), e.trailPct(), ts + e.maxHoldHours() * 3600L, price);
    }

    public double entryPrice() { return entryPrice; }
    public long entryTs() { return entryTs; }
    public double target() { return target; }
    public double stop() { return stop; }
    public double trailPct() { return trailPct; }
    public long maxHoldUntil() { return maxHoldUntil; }
    public double highWater() { return highWater; }

    public void observeHigh(double price) {
        highWater = Math.max(highWater, price);
    }

    /** Current trailing stop level, or NaN when the trade has no trailing stop. */
    public double trailingLevel() {
        return Double.isNaN(trailPct) ? Double.NaN : highWater * (1 - trailPct);
    }
}
