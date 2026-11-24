package com.familyx.stocks.service;

import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;

import java.util.List;

/** Published by the trading loop; the Discord bot turns them into alerts. */
public final class TradeEvents {
    private TradeEvents() {
    }

    public record Opened(Position position, StockRow stock, Assignment evidence) {
    }

    public record Closed(Position position, StockRow stock) {
    }

    /** A rotation rebalance on the main portfolio. Lists are in ranking order. */
    public record Rebalanced(long ts, long nextTs, List<Sold> sold, List<Bought> bought, List<Kept> kept) {
        public boolean changed() {
            return !sold.isEmpty() || !bought.isEmpty();
        }
    }

    public record Sold(StockRow stock, Position closed) {
    }

    public record Bought(StockRow stock, Position position, double gap, int rank) {
    }

    public record Kept(StockRow stock, Position position, double netIfSoldNow, int rank) {
    }

    /** Price collection has been failing for a while ({@code down}), or has recovered after such a stretch. */
    public record CollectionHealth(boolean down, String lastError, long sinceTs, long nowTs) {
    }
}
