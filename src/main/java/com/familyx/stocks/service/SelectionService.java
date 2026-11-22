package com.familyx.stocks.service;

import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.Bar;
import com.familyx.stocks.engine.Strategies;
import com.familyx.stocks.engine.StrategySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Re-picks each stock's strategy once a day from all stored hourly history (up to a year). Open positions keep
 * the strategy they were opened with; only new entries use the new assignment.
 */
@Service
public class SelectionService {
    private static final Logger log = LoggerFactory.getLogger(SelectionService.class);
    private static final int MAX_BARS = 24 * 366;

    private final StockRepository repo;

    public SelectionService(StockRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
    public void daily() {
        runSelection();
    }

    public synchronized void runSelection() {
        long now = System.currentTimeMillis() / 1000;
        long currentHour = now / 3600 * 3600;
        int assigned = 0;
        List<StockRow> stocks = repo.stocks();
        for (StockRow s : stocks) {
            List<Bar> bars = repo.bars(s.id(), currentHour, MAX_BARS);
            Optional<StrategySelector.Selection> sel =
                    StrategySelector.select(bars, Strategies.all(), StrategySelector.Criteria.defaults());
            if (sel.isPresent()) {
                var is = sel.get().inSample();
                var oos = sel.get().outOfSample();
                repo.saveAssignment(new Assignment(s.id(), sel.get().strategy().id(), is.tradeCount(),
                        is.winRate(), is.avgNet(), is.profitFactor(), is.totalReturn(), is.tStat(),
                        oos.tradeCount(), oos.totalReturn(), now));
                assigned++;
            } else {
                repo.deleteAssignment(s.id());
            }
        }
        log.info("Strategy selection done: {} of {} stocks have a strategy that beats the fee", assigned, stocks.size());
    }
}
