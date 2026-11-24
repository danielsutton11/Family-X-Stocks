package com.familyx.stocks.service;

import com.familyx.stocks.config.BotProperties;
import com.familyx.stocks.data.Book;
import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.Bar;
import com.familyx.stocks.engine.Entry;
import com.familyx.stocks.engine.Exit;
import com.familyx.stocks.engine.ExitReason;
import com.familyx.stocks.engine.Fees;
import com.familyx.stocks.engine.OpenTrade;
import com.familyx.stocks.engine.PriceExits;
import com.familyx.stocks.engine.Strategies;
import com.familyx.stocks.engine.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the dip strategy on the paper {@link Book#DIP} portfolio, so it can be compared with rotation (which
 * members follow). Every minute it checks open positions against their stops and targets at the live price.
 * When an hourly bar completes it also checks indicator exits and looks for new entries. It posts no alerts.
 *
 * <p>The live loop mirrors the backtester: same strategies, same exit rules, same fee.
 */
@Service
public class TradingService {
    private static final Logger log = LoggerFactory.getLogger(TradingService.class);
    private static final int LIVE_BARS = Math.max(800, Strategies.maxWarmup() * 4);

    private final StockRepository repo;
    private static final Book BOOK = Book.DIP;

    private final TransactionTemplate tx;
    private final BotProperties.Trading cfg;

    public TradingService(StockRepository repo, TransactionTemplate tx, BotProperties props) {
        this.repo = repo;
        this.tx = tx;
        this.cfg = props.trading();
    }

    /**
     * @param boundaryTs start of the hour that just began when the previous bar has completed, otherwise null
     */
    public synchronized void onMinute(Map<Integer, StockRow> stocks, long nowTs, Long boundaryTs) {
        double cash = repo.portfolio(BOOK, nowTs).cash();
        Set<Integer> held = new HashSet<>();

        for (Position p : repo.openPositions(BOOK)) {
            StockRow s = stocks.get(p.stockId());
            if (s == null) {
                held.add(p.stockId());
                continue;
            }
            double price = s.price();
            OpenTrade trade = p.toTrade();
            Exit exit = PriceExits.check(trade, price, price, price, nowTs);
            if (exit == null && boundaryTs != null) {
                Optional<Strategy> strategy = Strategies.byId(p.strategyId());
                if (strategy.isPresent() && strategy.get().exitOnClose(trade, repo.bars(p.stockId(), boundaryTs, LIVE_BARS))) {
                    exit = new Exit(ExitReason.SIGNAL, price);
                }
            }
            if (exit != null) {
                cash = close(p, s, price, exit.reason(), nowTs, cash);
            } else {
                held.add(p.stockId());
                if (price > p.highWater()) {
                    repo.updateHighWater(p.id(), price);
                }
            }
        }

        if (boundaryTs == null) {
            return;
        }

        double equity = cash;
        for (Position p : repo.openPositions(BOOK)) {
            StockRow s = stocks.get(p.stockId());
            equity += p.units() * (s == null ? p.entryPrice() : s.price());
        }
        repo.snapshotEquity(BOOK, boundaryTs, equity);

        for (Assignment a : repo.assignments()) {
            if (held.contains(a.stockId())) {
                continue;
            }
            StockRow s = stocks.get(a.stockId());
            Optional<Strategy> strategy = Strategies.byId(a.strategyId());
            if (s == null || strategy.isEmpty()) {
                continue;
            }
            List<Bar> bars = repo.bars(a.stockId(), boundaryTs, LIVE_BARS);
            Optional<Entry> entry = strategy.get().entry(bars);
            if (entry.isEmpty()) {
                continue;
            }
            double alloc = Math.min(cfg.positionSize() * equity, cash);
            if (alloc < cfg.minPosition() * equity) {
                log.info("Skipping {} entry: only {}% of equity left in cash", s.acronym(), Math.round(cash / equity * 100));
                continue;
            }
            cash = open(s, strategy.get(), entry.get(), a, alloc, equity, nowTs, cash);
        }
    }

    private double open(StockRow s, Strategy strategy, Entry e, Assignment evidence, double alloc, double equity,
                        long nowTs, double cash) {
        double price = s.price();
        double newCash = cash - alloc;
        tx.executeWithoutResult(status -> {
            repo.openPosition(BOOK, s.id(), strategy.id(), nowTs, price, alloc / price, alloc / equity,
                    nullIfNaN(e.target()), nullIfNaN(e.stop()), nullIfNaN(e.trailPct()),
                    nowTs + e.maxHoldHours() * 3600L, e.reason());
            repo.setCash(BOOK, newCash);
        });
        log.info("[paper dip] BUY {} at {} with {} ({})", s.acronym(), price, strategy.id(), e.reason());
        return newCash;
    }

    private double close(Position p, StockRow s, double price, ExitReason reason, long nowTs, double cash) {
        double proceeds = p.units() * price * (1 - Fees.SELL_FEE);
        double net = Fees.netReturn(p.entryPrice(), price);
        double newCash = cash + proceeds;
        tx.executeWithoutResult(status -> {
            repo.closePosition(p.id(), nowTs, price, reason.name(), net);
            repo.setCash(BOOK, newCash);
        });
        log.info("[paper dip] SELL {} at {} ({}), net {}%", s.acronym(), price, reason, Math.round(net * 10000) / 100.0);
        return newCash;
    }

    private static Double nullIfNaN(double d) {
        return Double.isNaN(d) ? null : d;
    }
}
