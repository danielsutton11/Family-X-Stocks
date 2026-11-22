package com.familyx.stocks.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogue of strategy variants the selector tests on each stock. The grid is kept deliberately small:
 * more variants means more chances to find one that fitted the past by luck.
 */
public final class Strategies {
    private static final Map<String, Strategy> BY_ID = new LinkedHashMap<>();

    static {
        for (int lookback : new int[]{72, 168}) {
            for (double z : new double[]{1.5, 2.0, 2.5}) {
                add(new MeanReversionStrategy(lookback, z, 0.03, 0.005, 14 * 24));
            }
        }
        add(new TrendStrategy(12, 72, 0.03, 30 * 24));
        add(new TrendStrategy(24, 168, 0.04, 30 * 24));
        add(new BreakoutStrategy(72, 24, 0.03, 30 * 24));
        add(new BreakoutStrategy(168, 72, 0.05, 30 * 24));
    }

    private Strategies() {
    }

    private static void add(Strategy s) {
        BY_ID.put(s.id(), s);
    }

    public static List<Strategy> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Optional<Strategy> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Largest warm-up across the catalogue: how many bars to load for live evaluation. */
    public static int maxWarmup() {
        return BY_ID.values().stream().mapToInt(Strategy::warmup).max().orElse(0);
    }
}
