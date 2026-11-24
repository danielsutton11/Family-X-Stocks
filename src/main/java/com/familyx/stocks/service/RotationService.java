package com.familyx.stocks.service;

import com.familyx.stocks.config.BotProperties;
import com.familyx.stocks.data.Book;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.Exit;
import com.familyx.stocks.engine.Fees;
import com.familyx.stocks.engine.OpenTrade;
import com.familyx.stocks.engine.PriceExits;
import com.familyx.stocks.engine.Rotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the main model portfolio that members follow: the rotation strategy. Every {@code everyHours}, at a fixed
 * time of day, it ranks all stocks by how far they sit below their 4-week average, sells holdings that have fallen
 * out of the top {@code keep}, and fills empty slots from the top {@code hold}. One Rebalance alert per run.
 *
 * <p>Positions left in the main book from the earlier dip strategy keep their stops and targets until they exit
 * or a rebalance replaces them.
 */
@Service
public class RotationService {
    private static final Logger log = LoggerFactory.getLogger(RotationService.class);
    private static final Book BOOK = Book.MAIN;
    private static final String NEXT_SETTING = "next_rebalance_ts";
    private static final String STARTED_SETTING = "rotation_started";

    private final StockRepository repo;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final BotProperties.Rotation cfg;
    private final Rotation.Params params;

    public RotationService(StockRepository repo, ApplicationEventPublisher events, TransactionTemplate tx,
                           BotProperties props) {
        this.repo = repo;
        this.events = events;
        this.tx = tx;
        this.cfg = props.rotation();
        this.params = cfg.params();
    }

    public Rotation.Params params() {
        return params;
    }

    /** When the next rebalance will run (epoch seconds), scheduling the first one if needed. */
    public long nextRebalanceTs(long nowTs) {
        return repo.setting(NEXT_SETTING).map(Long::parseLong).orElseGet(() -> {
            long day = nowTs / 86400 * 86400;
            long first = day + cfg.anchorHourUtc() * 3600L;
            if (first <= nowTs) {
                first += 86400;
            }
            repo.saveSetting(NEXT_SETTING, Long.toString(first));
            return first;
        });
    }

    /** Current ranking using completed hourly bars before {@code beforeTs}. */
    public List<Rotation.Rank> ranking(Map<Integer, StockRow> stocks, long beforeTs) {
        Map<Integer, Double> gaps = new HashMap<>();
        for (StockRow s : stocks.values()) {
            gaps.put(s.id(), Rotation.gap(repo.bars(s.id(), beforeTs, params.lookbackHours()), params.lookbackHours()));
        }
        return Rotation.rank(gaps);
    }

    public synchronized void onMinute(Map<Integer, StockRow> stocks, long nowTs, Long boundaryTs) {
        double cash = repo.portfolio(BOOK, nowTs).cash();
        cash = legacyExits(stocks, nowTs, cash);

        // First run of rotation: buy straight away rather than waiting for the first scheduled slot, then join
        // the fixed schedule at the first anchor time at least (every - 24) hours away.
        if (repo.setting(STARTED_SETTING).isEmpty()) {
            long hourStart = nowTs / 3600 * 3600;
            long next = firstAnchorAtOrAfter(nowTs + (params.everyHours() - 24) * 3600L);
            repo.saveSetting(NEXT_SETTING, Long.toString(next));
            rebalance(stocks, nowTs, hourStart, cash, next);
            repo.saveSetting(STARTED_SETTING, Long.toString(nowTs));
            return;
        }

        if (boundaryTs == null) {
            return;
        }
        repo.snapshotEquity(BOOK, boundaryTs, equity(stocks, cash));
        long next = nextRebalanceTs(nowTs);
        if (boundaryTs >= next) {
            while (next <= boundaryTs) {
                next += params.everyHours() * 3600L;
            }
            rebalance(stocks, boundaryTs, boundaryTs, cash, next);
            repo.saveSetting(NEXT_SETTING, Long.toString(next));
        }
    }

    private long firstAnchorAtOrAfter(long ts) {
        long anchor = ts / 86400 * 86400 + cfg.anchorHourUtc() * 3600L;
        return anchor >= ts ? anchor : anchor + 86400;
    }

    private double equity(Map<Integer, StockRow> stocks, double cash) {
        double eq = cash;
        for (Position p : repo.openPositions(BOOK)) {
            StockRow s = stocks.get(p.stockId());
            eq += p.units() * (s == null ? p.entryPrice() : s.price());
        }
        return eq;
    }

    /** Stops and targets on positions that came from the dip strategy before the switch to rotation. */
    private double legacyExits(Map<Integer, StockRow> stocks, long nowTs, double cash) {
        for (Position p : repo.openPositions(BOOK)) {
            if (p.target() == null && p.stop() == null && p.trailPct() == null) {
                continue;
            }
            StockRow s = stocks.get(p.stockId());
            if (s == null) {
                continue;
            }
            OpenTrade t = p.toTrade();
            Exit e = PriceExits.check(t, s.price(), s.price(), s.price(), nowTs);
            if (e == null) {
                if (s.price() > p.highWater()) {
                    repo.updateHighWater(p.id(), s.price());
                }
                continue;
            }
            cash = sell(p, s, nowTs, cash, e.reason().name());
            events.publishEvent(new TradeEvents.Closed(closedCopy(p, nowTs, s.price(), e.reason().name()), s));
        }
        return cash;
    }

    /**
     * @param nowTs        time of the rebalance (shown on the alert and stored on positions)
     * @param rankBeforeTs rank on completed hourly bars before this time
     * @param nextTs       when the following rebalance is due
     */
    private void rebalance(Map<Integer, StockRow> stocks, long nowTs, long rankBeforeTs, double cash, long nextTs) {
        List<Rotation.Rank> ranking = ranking(stocks, rankBeforeTs);
        Map<Integer, Rotation.Rank> byStock = new HashMap<>();
        ranking.forEach(r -> byStock.put(r.stockId(), r));
        List<Position> open = repo.openPositions(BOOK);
        Set<Integer> held = new HashSet<>();
        open.forEach(p -> held.add(p.stockId()));
        Rotation.Plan plan = Rotation.plan(ranking, held, params);

        List<TradeEvents.Sold> sold = new ArrayList<>();
        for (int stockId : plan.sell()) {
            Position p = open.stream().filter(x -> x.stockId() == stockId).findFirst().orElseThrow();
            StockRow s = stocks.get(stockId);
            cash = sell(p, s, nowTs, cash, "ROTATION");
            sold.add(new TradeEvents.Sold(s, closedCopy(p, nowTs, s.price(), "ROTATION")));
        }

        double value = equity(stocks, cash);
        List<TradeEvents.Bought> bought = new ArrayList<>();
        for (int stockId : plan.buy()) {
            StockRow s = stocks.get(stockId);
            double amount = Math.min(value / params.hold(), cash);
            if (s == null || amount <= value * 0.001) {
                break;
            }
            Rotation.Rank r = byStock.get(stockId);
            String reason = String.format(Locale.US, "%.1f%% below its %d-week average (rank #%d)",
                    -r.gap() * 100, params.lookbackHours() / 168, r.rank() + 1);
            double price = s.price();
            double newCash = cash - amount;
            double alloc = amount / value;
            Long id = tx.execute(status -> {
                long pid = repo.openPosition(BOOK, stockId, params.id(), nowTs, price, amount / price, alloc,
                        null, null, null, nowTs + 3650L * 86400, reason);
                repo.setCash(BOOK, newCash);
                return pid;
            });
            cash = newCash;
            Position pos = repo.openPositions(BOOK).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
            bought.add(new TradeEvents.Bought(s, pos, r.gap(), r.rank()));
            log.info("ROTATION BUY {} at {} ({})", s.acronym(), price, reason);
        }

        List<TradeEvents.Kept> kept = new ArrayList<>();
        for (int stockId : plan.keep()) {
            Position p = open.stream().filter(x -> x.stockId() == stockId).findFirst().orElseThrow();
            StockRow s = stocks.get(stockId);
            Rotation.Rank r = byStock.get(stockId);
            kept.add(new TradeEvents.Kept(s, p, Fees.netReturn(p.entryPrice(), s.price()), r == null ? -1 : r.rank()));
        }
        log.info("Rebalance done: sold {}, bought {}, kept {}", sold.size(), bought.size(), kept.size());
        events.publishEvent(new TradeEvents.Rebalanced(nowTs, nextTs, sold, bought, kept));
    }

    private double sell(Position p, StockRow s, long nowTs, double cash, String reason) {
        double price = s.price();
        double newCash = cash + p.units() * price * (1 - Fees.SELL_FEE);
        double net = Fees.netReturn(p.entryPrice(), price);
        tx.executeWithoutResult(status -> {
            repo.closePosition(p.id(), nowTs, price, reason, net);
            repo.setCash(BOOK, newCash);
        });
        log.info("{} SELL {} at {}, net {}%", reason, s.acronym(), price, Math.round(net * 10000) / 100.0);
        return newCash;
    }

    private static Position closedCopy(Position p, long ts, double price, String reason) {
        return new Position(p.id(), p.stockId(), p.strategyId(), "CLOSED", p.entryTs(), p.entryPrice(), p.units(),
                p.alloc(), p.target(), p.stop(), p.trailPct(), p.highWater(), p.maxHoldUntil(), p.reason(), ts, price,
                reason, Fees.netReturn(p.entryPrice(), price), p.book());
    }
}
