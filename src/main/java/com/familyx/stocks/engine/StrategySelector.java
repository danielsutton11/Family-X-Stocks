package com.familyx.stocks.engine;

import java.util.List;
import java.util.Optional;

/**
 * Picks the strategy (if any) to trade a stock with, using a walk-forward split:
 * <ol>
 *   <li>Backtest every variant on the first part of the history (in-sample).</li>
 *   <li>Keep variants with enough trades, a healthy profit factor and an average trade that clears the fee by a
 *       margin. Rank them by in-sample compounded return.</li>
 *   <li>Take the best one that also made money on the later, unseen part of the history (out-of-sample).</li>
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

    private StrategySelector() {
    }

    public static Optional<Selection> select(List<Bar> bars, List<Strategy> candidates, Criteria c) {
        if (bars.size() < 2) {
            return Optional.empty();
        }
        int split = (int) (bars.size() * c.inSampleFraction());
        long splitTs = bars.get(Math.min(split, bars.size() - 1)).ts();
        List<Bar> inSampleBars = bars.subList(0, split);

        Selection best = null;
        for (Strategy s : candidates) {
            if (inSampleBars.size() <= s.warmup() + 24) {
                continue;
            }
            BacktestResult is = Backtester.run(s, inSampleBars);
            if (is.tradeCount() < c.minTrades() || is.profitFactor() < c.minProfitFactor()
                    || is.avgNet() < c.minAvgNet() || is.tStat() < c.minTStat()) {
                continue;
            }
            BacktestResult oos = Backtester.run(s, bars, splitTs, Long.MAX_VALUE);
            if (oos.tradeCount() < c.minOutOfSampleTrades() || oos.totalReturn() <= 0
                    || oos.avgNet() < c.minAvgNet()) {
                continue;
            }
            if (best == null || is.totalReturn() > best.inSample().totalReturn()) {
                best = new Selection(s, is, oos);
            }
        }
        return Optional.ofNullable(best);
    }
}
