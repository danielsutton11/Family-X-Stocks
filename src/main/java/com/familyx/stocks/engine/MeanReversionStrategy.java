package com.familyx.stocks.engine;

import java.util.List;
import java.util.Optional;

/**
 * Buys when the close sits {@code zEntry} standard deviations below its {@code lookback}-hour average and the
 * gap back to the average is worth more than the fee plus {@code minEdge}. Targets the average at entry, with a
 * fixed stop-loss and a maximum hold.
 */
public class MeanReversionStrategy implements Strategy {
    private final int lookback;
    private final double zEntry;
    private final double stopPct;
    private final double minEdge;
    private final int maxHoldHours;

    public MeanReversionStrategy(int lookback, double zEntry, double stopPct, double minEdge, int maxHoldHours) {
        this.lookback = lookback;
        this.zEntry = zEntry;
        this.stopPct = stopPct;
        this.minEdge = minEdge;
        this.maxHoldHours = maxHoldHours;
    }

    @Override
    public String id() {
        return "meanrev-" + lookback + "-" + zEntry;
    }

    @Override
    public String displayName() {
        return "Mean reversion";
    }

    @Override
    public int warmup() {
        return lookback;
    }

    @Override
    public Optional<Entry> entry(List<Bar> bars) {
        if (bars.size() < lookback) {
            return Optional.empty();
        }
        double close = bars.get(bars.size() - 1).close();
        double mean = Indicators.sma(bars, lookback);
        double sd = Indicators.stdev(bars, lookback, mean);
        if (sd == 0) {
            return Optional.empty();
        }
        double z = (close - mean) / sd;
        double edge = Fees.netReturn(close, mean);
        if (z > -zEntry || edge < minEdge) {
            return Optional.empty();
        }
        String reason = String.format("price is %.1f standard deviations below its %d-hour average", -z, lookback);
        return Optional.of(new Entry(mean, close * (1 - stopPct), Double.NaN, maxHoldHours, reason));
    }

    @Override
    public boolean exitOnClose(OpenTrade trade, List<Bar> bars) {
        return false;
    }
}
