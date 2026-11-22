package com.familyx.stocks.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replays hourly bars through a strategy, one position at a time, fully invested while in a trade. Trades are
 * only counted when they enter at or after {@code fromTs}, but indicators can use earlier bars as warm-up,
 * which is what lets the selector score an out-of-sample window fairly.
 */
public final class Backtester {
    private Backtester() {
    }

    public record Trade(long entryTs, long exitTs, double entry, double exit, ExitReason reason, double netReturn) {
    }

    public static BacktestResult run(Strategy strategy, List<Bar> bars) {
        return run(strategy, bars, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static BacktestResult run(Strategy strategy, List<Bar> bars, long fromTs, long toTs) {
        List<Trade> trades = new ArrayList<>();
        OpenTrade open = null;
        int start = Math.max(1, strategy.warmup() - 1);

        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar.ts() >= toTs && open == null) {
                break;
            }
            List<Bar> upTo = bars.subList(0, i + 1);

            if (open != null) {
                Exit exit = PriceExits.check(open, bar.open(), bar.low(), bar.high(), bar.ts());
                if (exit == null) {
                    open.observeHigh(bar.high());
                    if (strategy.exitOnClose(open, upTo)) {
                        exit = new Exit(ExitReason.SIGNAL, bar.close());
                    }
                }
                if (exit != null) {
                    double price = Double.isNaN(exit.price()) ? bar.close() : exit.price();
                    trades.add(new Trade(open.entryTs(), bar.ts(), open.entryPrice(), price, exit.reason(),
                            Fees.netReturn(open.entryPrice(), price)));
                    open = null;
                }
                continue; // never exit and re-enter on the same bar
            }

            if (bar.ts() < fromTs || bar.ts() >= toTs) {
                continue;
            }
            Optional<Entry> entry = strategy.entry(upTo);
            if (entry.isPresent()) {
                open = OpenTrade.open(entry.get(), bar.close(), bar.ts());
            }
        }

        if (open != null) {
            // Mark the unfinished trade to the last close so open risk is not hidden from the score.
            Bar last = bars.get(bars.size() - 1);
            trades.add(new Trade(open.entryTs(), last.ts(), open.entryPrice(), last.close(), null,
                    Fees.netReturn(open.entryPrice(), last.close())));
        }

        double buyHold = Double.NaN;
        int first = firstIndexAtOrAfter(bars, fromTs, start);
        if (first < bars.size()) {
            buyHold = Fees.netReturn(bars.get(first).close(), bars.get(bars.size() - 1).close());
        }
        return BacktestResult.of(strategy.id(), trades, buyHold);
    }

    private static int firstIndexAtOrAfter(List<Bar> bars, long ts, int min) {
        for (int i = min; i < bars.size(); i++) {
            if (bars.get(i).ts() >= ts) {
                return i;
            }
        }
        return bars.size();
    }
}
