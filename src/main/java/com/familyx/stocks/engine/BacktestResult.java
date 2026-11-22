package com.familyx.stocks.engine;

import java.util.List;

/**
 * Summary of a backtest. Returns are fractions (0.05 = 5%) and already net of the sell fee.
 *
 * @param totalReturn  compounded return of taking every trade with the full stake
 * @param profitFactor gross wins divided by gross losses (infinite when nothing lost)
 * @param maxDrawdown  worst peak-to-trough fall of the compounded trade curve, as a positive fraction
 * @param buyHold      return of buying at the start of the window and holding to the end, after the fee
 * @param tStat        average trade return divided by its standard error: how unlikely the edge is to be luck
 */
public record BacktestResult(String strategyId, List<Backtester.Trade> trades, int wins, double winRate,
                             double avgNet, double profitFactor, double totalReturn, double maxDrawdown,
                             double buyHold, double tStat) {

    public static BacktestResult of(String strategyId, List<Backtester.Trade> trades, double buyHold) {
        int wins = 0;
        double gains = 0;
        double losses = 0;
        double sum = 0;
        double equity = 1;
        double peak = 1;
        double maxDd = 0;
        for (Backtester.Trade t : trades) {
            double r = t.netReturn();
            sum += r;
            if (r > 0) {
                wins++;
                gains += r;
            } else {
                losses -= r;
            }
            equity *= (1 + r);
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, (peak - equity) / peak);
        }
        int n = trades.size();
        double pf = losses == 0 ? (gains > 0 ? Double.POSITIVE_INFINITY : 0) : gains / losses;
        double avg = n == 0 ? 0 : sum / n;
        double tStat = 0;
        if (n > 1) {
            double sq = 0;
            for (Backtester.Trade t : trades) {
                double d = t.netReturn() - avg;
                sq += d * d;
            }
            double sd = Math.sqrt(sq / (n - 1));
            tStat = sd == 0 ? (avg > 0 ? Double.POSITIVE_INFINITY : 0) : avg / (sd / Math.sqrt(n));
        }
        return new BacktestResult(strategyId, List.copyOf(trades), wins, n == 0 ? 0 : (double) wins / n,
                avg, pf, equity - 1, maxDd, buyHold, tStat);
    }

    public int tradeCount() {
        return trades.size();
    }
}
