package com.familyx.stocks.engine;

/** One hourly OHLC bar. {@code ts} is the epoch second at the start of the hour. */
public record Bar(long ts, double open, double high, double low, double close) {
}
