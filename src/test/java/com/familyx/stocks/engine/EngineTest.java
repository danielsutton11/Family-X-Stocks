package com.familyx.stocks.engine;

import com.familyx.stocks.discord.Fmt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {

    /** Minute-level simulation rolled into hourly bars, calibrated to Torn's ~0.1%/hour volatility. */
    static List<Bar> series(String kind, long seed, int n) {
        Random r = new Random(seed);
        List<Bar> bars = new ArrayList<>();
        double p = 500;
        double drift = 0;
        long ts = 1_700_000_000L / 3600 * 3600;
        for (int i = 0; i < n; i++) {
            double open = p, hi = p, lo = p;
            for (int m = 0; m < 60; m++) {
                double step = switch (kind) {
                    case "trend" -> {
                        if (i % 500 == 0 && m == 0) {
                            drift = (r.nextBoolean() ? 1 : -1) * 0.0000045;
                        }
                        yield p * (drift + 0.00013 * r.nextGaussian());
                    }
                    default -> p * 0.00025 * r.nextGaussian();
                };
                p = Math.max(1, p + step);
                hi = Math.max(hi, p);
                lo = Math.min(lo, p);
            }
            bars.add(new Bar(ts + i * 3600L, open, hi, lo, p));
        }
        return bars;
    }

    @Test
    void feeIsChargedOnlyOnTheSale() {
        assertEquals(-0.001, Fees.netReturn(100, 100), 1e-12);
        assertEquals(0, Fees.netReturn(100, Fees.breakEven(100)), 1e-12);
    }

    @Test
    void stopBeatsTargetWhenABarTouchesBoth() {
        OpenTrade t = new OpenTrade(100, 0, 105, 97, Double.NaN, 1_000_000, 100);
        assertEquals(ExitReason.STOP, PriceExits.check(t, 100, 96, 106, 10).reason());
        assertEquals(95, PriceExits.check(t, 95, 94, 96, 10).price(), 1e-12, "a gap through the stop fills at the open");
        assertEquals(105, PriceExits.check(t, 100, 99, 106, 10).price(), 1e-12);
    }

    @Test
    void trailingStopFollowsTheHigh() {
        OpenTrade t = new OpenTrade(100, 0, Double.NaN, Double.NaN, 0.03, 1_000_000, 110);
        assertEquals(ExitReason.TRAILING_STOP, PriceExits.check(t, 107, 106.6, 107, 10).reason());
    }

    @Test
    void selectorRarelyTradesRandomWalks() {
        int picked = 0;
        for (long seed = 1; seed <= 20; seed++) {
            if (StrategySelector.select(series("random", seed, 8760), Strategies.all(),
                    StrategySelector.Criteria.defaults()).isPresent()) {
                picked++;
            }
        }
        assertTrue(picked <= 2, "selector picked a strategy for " + picked + " of 20 random walks");
    }

    @Test
    void selectorFindsTrends() {
        int picked = 0;
        for (long seed = 1; seed <= 20; seed++) {
            if (StrategySelector.select(series("trend", seed, 8760), Strategies.all(),
                    StrategySelector.Criteria.defaults()).isPresent()) {
                picked++;
            }
        }
        assertTrue(picked >= 15, "selector only found " + picked + " of 20 trends");
    }

    @Test
    void budgetsParse() {
        assertEquals(2_000_000_000d, Fmt.parseBudget("2b"));
        assertEquals(500_000_000d, Fmt.parseBudget("500M"));
        assertEquals(1_500d, Fmt.parseBudget("1.5k"));
        assertEquals(2_000_000d, Fmt.parseBudget("$2,000,000"));
    }

    @Test
    void rotationKeepsHoldingsUntilTheyLeaveTheKeepZone() {
        // stock id = position in ranking; ids 0..11, 0 is furthest below average
        java.util.Map<Integer, Double> gaps = new java.util.HashMap<>();
        for (int i = 0; i < 12; i++) {
            gaps.put(i, -0.05 + i * 0.01);
        }
        var ranking = Rotation.rank(gaps);
        var p = new Rotation.Params(672, 72, 5, 10);
        // holding 3 (rank 3, keep), 8 (rank 8, keep) and 11 (rank 11, sell)
        var plan = Rotation.plan(ranking, java.util.Set.of(3, 8, 11), p);
        assertEquals(java.util.List.of(11), plan.sell());
        assertEquals(java.util.List.of(0, 1, 2), plan.buy());
        assertEquals(java.util.List.of(3, 8), plan.keep());
    }
}
