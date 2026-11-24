package com.familyx.stocks.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rotation strategy: hold the {@code hold} stocks whose price is furthest below its {@code lookback}-hour
 * average, and only sell a holding once it ranks worse than {@code keep} (so stocks aren't churned over small
 * rank changes). Research on a year of hourly prices: about 24%/yr out of sample, worst dip about 1%.
 */
public final class Rotation {

    public record Params(int lookbackHours, int everyHours, int hold, int keep) {
        public String id() {
            return "rotation-" + lookbackHours + "-" + hold + "-" + keep;
        }
    }

    /** One stock's place in the ranking. {@code gap} is ln(price / lookback average): negative = below average. */
    public record Rank(int stockId, double gap, int rank) {
    }

    public record Plan(List<Integer> sell, List<Integer> buy, List<Integer> keep, List<Rank> ranking) {
    }

    private Rotation() {
    }

    /** Gap of the last close below the average of the last {@code lookback} closes, or NaN without enough history. */
    public static double gap(List<Bar> bars, int lookback) {
        if (bars.size() < lookback) {
            return Double.NaN;
        }
        double mean = Indicators.sma(bars, lookback);
        return Math.log(bars.get(bars.size() - 1).close() / mean);
    }

    /** Ranks stocks from most below average (rank 0) to most above; stocks without enough history are left out. */
    public static List<Rank> rank(Map<Integer, Double> gaps) {
        List<Map.Entry<Integer, Double>> ok = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : gaps.entrySet()) {
            if (e.getValue() != null && Double.isFinite(e.getValue())) {
                ok.add(e);
            }
        }
        ok.sort(Map.Entry.comparingByValue());
        List<Rank> out = new ArrayList<>();
        for (int i = 0; i < ok.size(); i++) {
            out.add(new Rank(ok.get(i).getKey(), ok.get(i).getValue(), i));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Decides a rebalance. Held stocks ranked {@code keep} or worse are sold (a held stock missing from the ranking
     * is kept, since there's no evidence against it). Freed slots are filled from the top of the ranking.
     */
    public static Plan plan(List<Rank> ranking, Set<Integer> held, Params p) {
        List<Integer> sell = new ArrayList<>();
        List<Integer> keep = new ArrayList<>();
        for (int stock : held) {
            Rank r = ranking.stream().filter(x -> x.stockId() == stock).findFirst().orElse(null);
            if (r != null && r.rank() >= p.keep()) {
                sell.add(stock);
            } else {
                keep.add(stock);
            }
        }
        int slots = p.hold() - keep.size();
        List<Integer> buy = new ArrayList<>();
        for (Rank r : ranking) {
            if (slots <= 0 || r.rank() >= p.hold()) {
                break;
            }
            if (!held.contains(r.stockId())) {
                buy.add(r.stockId());
                slots--;
            }
        }
        keep.sort(Comparator.comparingInt(s -> rankOf(ranking, s)));
        return new Plan(List.copyOf(sell), List.copyOf(buy), List.copyOf(keep), ranking);
    }

    private static int rankOf(List<Rank> ranking, int stock) {
        return ranking.stream().filter(x -> x.stockId() == stock).mapToInt(Rank::rank).findFirst().orElse(Integer.MAX_VALUE);
    }
}
