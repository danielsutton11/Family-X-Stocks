package com.familyx.stocks.service;

import com.familyx.stocks.config.BotProperties;
import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.EquityPoint;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.BacktestResult;
import com.familyx.stocks.engine.Bar;
import com.familyx.stocks.engine.Fees;
import com.familyx.stocks.engine.Strategies;
import com.familyx.stocks.engine.Strategy;
import com.familyx.stocks.engine.StrategySelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Builds one JSON file with everything needed to review and tune the bot: settings, portfolio summary, every
 * trade, the hourly equity curve, and every strategy's backtest on every stock with the rule it passed or failed.
 */
@Service
public class ExportService {
    private static final int MAX_BARS = 24 * 366;

    private final StockRepository repo;
    private final PortfolioService portfolio;
    private final BotProperties props;
    private final ObjectMapper mapper;

    public ExportService(StockRepository repo, PortfolioService portfolio, BotProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.portfolio = portfolio;
        this.props = props;
        this.mapper = mapper;
    }

    public record Export(byte[] json, int trades, int equityPoints, int stocks, int strategies,
                         byte[] barsCsvGz, int barRows) {
    }

    public Export build() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        long currentHour = now / 3600 * 3600;
        Map<Integer, StockRow> stocks = portfolio.stocksById();
        var criteria = StrategySelector.Criteria.defaults();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "family-x-stock-bot-export/1");
        root.put("exported_at", iso(now));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("position_size", props.trading().positionSize());
        settings.put("min_position", props.trading().minPosition());
        settings.put("sell_fee", Fees.SELL_FEE);
        settings.put("backfill_months", props.tornsy().backfillMonths());
        settings.put("selection_criteria", criteria);
        settings.put("strategies", Strategies.all().stream().map(Strategy::id).toList());
        root.put("settings", settings);

        var snap = portfolio.snapshot();
        int[] closed = repo.closedCounts();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inception", iso(snap.inceptionTs()));
        summary.put("equity", round(snap.equity()));
        summary.put("return", round(snap.equity() - 1));
        summary.put("buy_and_hold_return", round(portfolio.benchmarkSinceInception(snap.inceptionTs())));
        summary.put("cash_weight", round(snap.cashWeight()));
        summary.put("open_positions", snap.holdings().size());
        summary.put("closed_trades", closed[0]);
        summary.put("winning_trades", closed[1]);
        summary.put("max_drawdown", round(repo.maxDrawdown()));
        root.put("portfolio", summary);

        List<Map<String, Object>> trades = new ArrayList<>();
        for (Position p : repo.allPositions()) {
            StockRow s = stocks.get(p.stockId());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", p.id());
            t.put("stock", s == null ? String.valueOf(p.stockId()) : s.acronym());
            t.put("strategy", p.strategyId());
            t.put("status", p.status());
            t.put("entry_time", iso(p.entryTs()));
            t.put("entry_price", p.entryPrice());
            t.put("allocation", round(p.alloc()));
            t.put("target", p.target());
            t.put("stop", p.stop());
            t.put("trail_pct", p.trailPct());
            t.put("high_water", p.highWater());
            t.put("reason", p.reason());
            if ("CLOSED".equals(p.status())) {
                t.put("exit_time", iso(p.exitTs()));
                t.put("exit_price", p.exitPrice());
                t.put("exit_reason", p.exitReason());
                t.put("net_return", round(p.netReturn()));
                t.put("hours_held", (p.exitTs() - p.entryTs()) / 3600.0);
            } else if (s != null) {
                t.put("current_price", s.price());
                t.put("net_if_sold_now", round(Fees.netReturn(p.entryPrice(), s.price())));
            }
            trades.add(t);
        }
        root.put("trades", trades);

        List<EquityPoint> curve = repo.equityCurve();
        root.put("equity_curve", curve.stream()
                .map(e -> Arrays.asList(iso(e.ts()), round(e.equity()))).toList());

        Map<Integer, Assignment> assigned = new LinkedHashMap<>();
        repo.assignments().forEach(a -> assigned.put(a.stockId(), a));
        List<Map<String, Object>> backtests = new ArrayList<>();
        for (StockRow s : stocks.values()) {
            List<Bar> bars = repo.bars(s.id(), currentHour, MAX_BARS);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("stock", s.acronym());
            b.put("price", s.price());
            b.put("hours_of_history", bars.size());
            b.put("assigned_strategy", assigned.containsKey(s.id()) ? assigned.get(s.id()).strategyId() : null);
            b.put("buy_and_hold_full_period", bars.isEmpty() ? null
                    : round(Fees.netReturn(bars.get(0).close(), bars.get(bars.size() - 1).close())));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var c : StrategySelector.evaluate(bars, Strategies.all(), criteria)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("strategy", c.strategy().id());
                r.put("result", c.passed() ? "passed" : c.rejectedBecause());
                r.put("in_sample", stats(c.inSample()));
                r.put("out_of_sample", stats(c.outOfSample()));
                rows.add(r);
            }
            b.put("strategies", rows);
            backtests.add(b);
        }
        root.put("backtests", backtests);

        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        BarsFile bars = barsCsvGz(stocks);
        return new Export(json, trades.size(), curve.size(), backtests.size(), Strategies.all().size(),
                bars.bytes(), bars.rows());
    }

    private record BarsFile(byte[] bytes, int rows) {
    }

    /**
     * Every stored hourly bar as gzipped CSV ({@code stock,ts,open,high,low,close}, ts in epoch seconds), so
     * portfolio-level simulations can be run offline with the bot's own engine. Read one stock at a time to keep
     * memory flat; about 300k rows and 3 MB compressed for a year of 35 stocks.
     */
    private BarsFile barsCsvGz(Map<Integer, StockRow> stocks) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int rows = 0;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(buffer), StandardCharsets.UTF_8)) {
            w.write("stock,ts,open,high,low,close\n");
            for (StockRow s : stocks.values()) {
                for (Bar b : repo.bars(s.id(), Long.MAX_VALUE, Integer.MAX_VALUE)) {
                    w.write(s.acronym() + "," + b.ts() + "," + b.open() + "," + b.high() + "," + b.low() + ","
                            + b.close() + "\n");
                    rows++;
                }
            }
        }
        return new BarsFile(buffer.toByteArray(), rows);
    }

    private static Map<String, Object> stats(BacktestResult r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trades", r.tradeCount());
        m.put("win_rate", round(r.winRate()));
        m.put("avg_net", round(r.avgNet()));
        m.put("profit_factor", round(r.profitFactor()));
        m.put("t_stat", round(r.tStat()));
        m.put("total_return", round(r.totalReturn()));
        m.put("max_drawdown", round(r.maxDrawdown()));
        m.put("buy_and_hold", round(r.buyHold()));
        return m;
    }

    /** Rounds to 5 decimal places; infinities and NaN become null so the file stays valid JSON. */
    private static Double round(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) {
            return null;
        }
        return Math.round(d * 100_000d) / 100_000d;
    }

    private static String iso(Long ts) {
        return ts == null ? null : Instant.ofEpochSecond(ts).toString();
    }
}
