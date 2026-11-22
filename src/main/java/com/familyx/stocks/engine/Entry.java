package com.familyx.stocks.engine;

/**
 * A strategy's decision to buy.
 *
 * @param target       take-profit price, or NaN for none
 * @param stop         fixed stop-loss price, or NaN for none
 * @param trailPct     trailing stop as a fraction below the highest price since entry, or NaN for none
 * @param maxHoldHours force an exit after this many hours
 * @param reason       short human explanation for the alert
 */
public record Entry(double target, double stop, double trailPct, int maxHoldHours, String reason) {
}
