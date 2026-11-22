package com.familyx.stocks.engine;

/** Torn charges nothing to buy and 0.1% of the sale value to sell. */
public final class Fees {
    public static final double SELL_FEE = 0.001;

    private Fees() {
    }

    /** Net return of buying at {@code entry} and selling at {@code exit}, after the sell fee. */
    public static double netReturn(double entry, double exit) {
        return (exit * (1 - SELL_FEE) - entry) / entry;
    }

    /** Price a stock must reach before selling it breaks even. */
    public static double breakEven(double entry) {
        return entry / (1 - SELL_FEE);
    }
}
