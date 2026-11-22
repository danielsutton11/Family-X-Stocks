package com.familyx.stocks.engine;

import java.util.List;
import java.util.Optional;

/**
 * A trading rule evaluated on completed hourly bars. The last element of {@code bars} is the most recently
 * completed bar; entries fill at that bar's close (backtest) or the current price (live).
 */
public interface Strategy {
    /** Stable id stored with assignments and positions, e.g. {@code meanrev-168-2.0}. */
    String id();

    /** Family name shown in Discord. */
    String displayName();

    /** Bars needed before the strategy can produce a signal. */
    int warmup();

    Optional<Entry> entry(List<Bar> bars);

    /** Indicator-based exit checked at each bar close, on top of the price-level exits. */
    boolean exitOnClose(OpenTrade trade, List<Bar> bars);
}
