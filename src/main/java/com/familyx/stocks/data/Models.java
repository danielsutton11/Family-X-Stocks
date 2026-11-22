package com.familyx.stocks.data;

import com.familyx.stocks.engine.OpenTrade;

/** Row types read from and written to Postgres. */
public final class Models {
    private Models() {
    }

    public record StockRow(int id, String acronym, String name, double price, long updatedTs) {
    }

    public record Assignment(int stockId, String strategyId, int isTrades, double isWinRate, double isAvgNet,
                             double isProfitFactor, double isTotalReturn, double isTStat, int oosTrades,
                             double oosTotalReturn, long selectedTs) {
    }

    public record Position(long id, int stockId, String strategyId, String status, long entryTs, double entryPrice,
                           double units, double alloc, Double target, Double stop, Double trailPct,
                           double highWater, long maxHoldUntil, String reason, Long exitTs, Double exitPrice,
                           String exitReason, Double netReturn) {

        public OpenTrade toTrade() {
            return new OpenTrade(entryPrice, entryTs, nanIfNull(target), nanIfNull(stop), nanIfNull(trailPct),
                    maxHoldUntil, highWater);
        }

        private static double nanIfNull(Double d) {
            return d == null ? Double.NaN : d;
        }
    }

    public record PortfolioState(double cash, long inceptionTs) {
    }

    public record EquityPoint(long ts, double equity) {
    }
}
