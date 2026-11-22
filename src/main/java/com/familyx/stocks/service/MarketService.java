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
        repo.recordMinute(quotes, now, hour);
        return now;
    }

    /**
     * Walks the backfill period in fixed windows and fetches any window that is less than 90% covered. Safe to
     * run on every start: complete windows are skipped, and gaps (including ones left by earlier runs) get filled.
     */
    private void backfill() {
        long currentHour = System.currentTimeMillis() / 1000 / 3600 * 3600;
        long since = currentHour - props.tornsy().backfillMonths() * 30L * 86400;
        long window = TornsyClient.WINDOW_SECONDS;
        for (StockRow s : repo.stocks()) {
            int added = 0;
            try {
                for (long from = since; from < currentHour; from += window) {
                    // The live collector owns the current hour's bar.
                    long to = Math.min(from + window, currentHour);
                    long expected = (to - from) / 3600;
                    if (repo.countBars(s.id(), from, to) >= expected * 0.9) {
                        continue;
                    }
                    List<Bar> bars = tornsy.hourlyBars(s.acronym(), from, to);
                    repo.insertBarsIfAbsent(s.id(), bars);
                    added += bars.size();
                    Thread.sleep(300); // be polite to a free community service
                }
                long total = repo.countBars(s.id(), since, currentHour);
                log.info("History for {}: {} hourly bars over the last {} months ({} fetched now)",
                        s.acronym(), total, props.tornsy().backfillMonths(), added);
            } catch (Exception e) {
                log.warn("Backfill failed for {}: {}", s.acronym(), e.getMessage());
            }
        }
    }

}
