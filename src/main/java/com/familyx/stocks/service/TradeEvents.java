package com.familyx.stocks.service;

import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;

/** Published by the trading loop; the Discord bot turns them into alerts. */
public final class TradeEvents {
    private TradeEvents() {
    }

    public record Opened(Position position, StockRow stock, Assignment evidence) {
    }

    public record Closed(Position position, StockRow stock) {
    }
}
