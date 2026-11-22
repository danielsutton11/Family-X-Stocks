package com.familyx.stocks.service;

import com.familyx.stocks.config.BotProperties;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.Bar;
import com.familyx.stocks.torn.StockQuote;
import com.familyx.stocks.torn.TornClient;
import com.familyx.stocks.torn.TornsyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Collects prices once a minute (one Torn API call for all stocks), builds hourly bars, and drives the trading
 * loop. On startup it backfills history from Tornsy and runs strategy selection.
 */
@Service
public class MarketService {
    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    private final TornClient torn;
    private final TornsyClient tornsy;
    private final StockRepository repo;
    private final TradingService trading;
    private final SelectionService selection;
    private final PortfolioService portfolio;
    private final BotProperties props;

    private volatile long lastHourTs = 0;
    private volatile boolean ready = false;

    public MarketService(TornClient torn, TornsyClient tornsy, StockRepository repo, TradingService trading,
                         SelectionService selection, PortfolioService portfolio, BotProperties props) {
        this.torn = torn;
        this.tornsy = tornsy;
        this.repo = repo;
        this.trading = trading;
        this.selection = selection;
        this.portfolio = portfolio;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        try {
            collect();
        } catch (Exception e) {
            log.error("First price collection failed; check TORN_API_KEY", e);
        }
        if (props.tornsy().backfillEnabled()) {
            backfill();
        }
        selection.runSelection();
        lastHourTs = System.currentTimeMillis() / 1000 / 3600 * 3600;
        ready = true;
    }

    /** Runs 15 seconds past each minute, after Torn's once-a-minute price update has landed. */
    @Scheduled(cron = "15 * * * * *")
    public void tick() {
        if (!ready) {
            return;
        }
        try {
            long now = collect();
            long hour = now / 3600 * 3600;
            Long boundary = null;
            if (hour > lastHourTs) {
                boundary = hour;
                lastHourTs = hour;
            }
            Map<Integer, StockRow> stocks = portfolio.stocksById();
            trading.onMinute(stocks, now, boundary);
        } catch (Exception e) {
            log.warn("Price collection failed: {}", e.getMessage());
        }
    }

    private long collect() throws Exception {
        List<StockQuote> quotes = torn.fetchStocks();
        long now = System.currentTimeMillis() / 1000 / 60 * 60;
        long hour = now / 3600 * 3600;
        for (StockQuote q : quotes) {
            repo.upsertStock(q.id(), q.acronym(), q.name(), q.price(), now);
            repo.insertTick(q.id(), now, q.price());
            repo.addToHourBar(q.id(), hour, q.price());
        }
        return now;
    }

    private void backfill() {
        long since = System.currentTimeMillis() / 1000 - props.tornsy().backfillMonths() * 30L * 86400;
        for (StockRow s : repo.stocks()) {
            // Skip stocks that already have history reaching back to the backfill window (with a week of slack).
            if (repo.oldestBarTs(s.id()) <= since + 7 * 86400) {
                continue;
            }
            try {
                List<Bar> bars = tornsy.hourlyBars(s.acronym(), since);
                long currentHour = System.currentTimeMillis() / 1000 / 3600 * 3600;
                // The live collector owns the current hour's bar.
                repo.insertBarsIfAbsent(s.id(), bars.stream().filter(b -> b.ts() < currentHour).toList());
                log.info("Backfilled {} hourly bars for {}", bars.size(), s.acronym());
            } catch (Exception e) {
                log.warn("Backfill failed for {}: {}", s.acronym(), e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "UTC")
    public void pruneTicks() {
        long cutoff = System.currentTimeMillis() / 1000 - props.trading().tickRetentionDays() * 86400L;
        int n = repo.pruneTicks(cutoff);
        log.info("Pruned {} old minute ticks", n);
    }
}
