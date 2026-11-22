package com.familyx.stocks.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the strategy (if any) to trade a stock with, using a walk-forward split:
 * <ol>
 *   <li>Backtest every variant on the first part of the history (in-sample).</li>
 *   <li>Keep variants with enough trades, a healthy profit factor, an average trade that clears the fee by a
 *       margin, and a t-stat high enough that the edge is unlikely to be luck.</li>
 *   <li>Of those, keep the ones that also made money on the later, unseen part of the history (out-of-sample),
 *       and take the one with the best in-sample compounded return.</li>
 * </ol>
 * A stock with no variant passing both halves gets no strategy, and the bot stays out of it.
 */
public final class StrategySelector {

    /**
     * @param minTStat the in-sample edge must be this many standard errors above zero. Ten variants are tested
     *                 per stock, so a loose bar here lets random noise through.
     */
    public record Criteria(double inSampleFraction, int minTrades, double minProfitFactor, double minAvgNet,
                           double minTStat, int minOutOfSampleTrades) {
        public static Criteria defaults() {
            return new Criteria(0.7, 6, 1.3, 0.002, 2.0, 2);
        }
    }

    public record Selection(Strategy strategy, BacktestResult inSample, BacktestResult outOfSample) {
    }

    /**
     * One variant's result on one stock. {@code rejectedBecause} is null when the variant passed every rule;
     * otherwise it names the first rule it failed. {@code outOfSample} is null when there wasn't enough history.
     */
    public record Candidate(Strategy strategy, BacktestResult inSample, BacktestResult outOfSample,
                            String rejectedBecause) {
        public boolean passed() {
            return rejectedBecause == null;
        }
    }

    private StrategySelector() {
    }

    public static Optional<Selection> select(List<Bar> bars, List<Strategy> candidates, Criteria c) {
        return evaluate(bars, candidates, c).stream()
                .filter(Candidate::passed)
                .max(Comparator.comparingDouble(x -> x.inSample().totalReturn()))
                .map(x -> new Selection(x.strategy(), x.inSample(), x.outOfSample()));
    }

    /** Scores every candidate, including the ones that fail, so near-misses can be inspected. */
    public static List<Candidate> evaluate(List<Bar> bars, List<Strategy> candidates, Criteria c) {
        List<Candidate> out = new ArrayList<>();
        if (bars.size() < 2) {
            return out;
        }
        int split = (int) (bars.size() * c.inSampleFraction());
        long splitTs = bars.get(Math.min(split, bars.size() - 1)).ts();
        List<Bar> inSampleBars = bars.subList(0, split);

        for (Strategy s : candidates) {
            if (inSampleBars.size() <= s.warmup() + 24) {
                out.add(new Candidate(s, null, null, "not enough history"));
                continue;
            }
            BacktestResult is = Backtester.run(s, inSampleBars);
            BacktestResult oos = Backtester.run(s, bars, splitTs, Long.MAX_VALUE);
            out.add(new Candidate(s, is, oos, firstFailure(is, oos, c)));
        }
        return out;
    }

    private static String firstFailure(BacktestResult is, BacktestResult oos, Criteria c) {
        if (is.tradeCount() < c.minTrades()) {
            return String.format(Locale.ROOT, "in-sample trades %d < %d", is.tradeCount(), c.minTrades());
        }
        if (is.profitFactor() < c.minProfitFactor()) {
            return String.format(Locale.ROOT, "in-sample profit factor %.2f < %.2f", is.profitFactor(), c.minProfitFactor());
        }
        if (is.avgNet() < c.minAvgNet()) {
            return String.format(Locale.ROOT, "in-sample avg trade %.3f%% < %.3f%%", is.avgNet() * 100, c.minAvgNet() * 100);
        }
        if (is.tStat() < c.minTStat()) {
            return String.format(Locale.ROOT, "in-sample t-stat %.2f < %.2f", is.tStat(), c.minTStat());
        }
        if (oos.tradeCount() < c.minOutOfSampleTrades()) {
            return String.format(Locale.ROOT, "out-of-sample trades %d < %d", oos.tradeCount(), c.minOutOfSampleTrades());
        }
        if (oos.totalReturn() <= 0) {
            return String.format(Locale.ROOT, "out-of-sample return %.2f%% <= 0", oos.totalReturn() * 100);
        }
        if (oos.avgNet() < c.minAvgNet()) {
            return String.format(Locale.ROOT, "out-of-sample avg trade %.3f%% < %.3f%%", oos.avgNet() * 100, c.minAvgNet() * 100);
        }
        return null;
    }
}
