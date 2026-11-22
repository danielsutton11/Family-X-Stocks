package com.familyx.stocks.engine;

/**
 * Price-level exits (stop, trailing stop, target, max hold). The backtester passes a bar's open, low and high;
 * the live loop passes the current price for all three. Stops are checked before targets, so a bar that
 * touches both counts as a loss, which keeps backtests on the pessimistic side.
 */
public final class PriceExits {
    private PriceExits() {
    }

    public static Exit check(OpenTrade t, double open, double low, double high, long ts) {
        double stopLevel = maxIgnoringNaN(t.stop(), t.trailingLevel());
        if (!Double.isNaN(stopLevel) && low <= stopLevel) {
            // If the price gapped through the stop, the fill is the open, not the stop.
            double fill = Math.min(open, stopLevel);
            boolean trailing = !Double.isNaN(t.trailingLevel())
                    && (Double.isNaN(t.stop()) || t.trailingLevel() > t.stop());
            return new Exit(trailing ? ExitReason.TRAILING_STOP : ExitReason.STOP, fill);
        }
        if (!Double.isNaN(t.target()) && high >= t.target()) {
            return new Exit(ExitReason.TARGET, Math.max(open, t.target()));
        }
        if (ts >= t.maxHoldUntil()) {
            return new Exit(ExitReason.MAX_HOLD, Double.NaN);
        }
        return null;
    }

    private static double maxIgnoringNaN(double a, double b) {
        if (Double.isNaN(a)) {
            return b;
        }
        if (Double.isNaN(b)) {
            return a;
        }
        return Math.max(a, b);
    }
}
