package com.familyx.stocks.service;

import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.Fees;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-side views of the model portfolio used by Discord commands. */
@Service
public class PortfolioService {
    private final StockRepository repo;

    public PortfolioService(StockRepository repo) {
        this.repo = repo;
    }

    public record Holding(Position position, StockRow stock, double price, double value, double weight,
                          double netIfSoldNow) {
    }

    public record Snapshot(double equity, double cash, List<Holding> holdings, long inceptionTs) {
        public double cashWeight() {
            return cash / equity;
        }
    }

    public Snapshot snapshot() {
        Map<Integer, StockRow> stocks = stocksById();
        var state = repo.portfolio(System.currentTimeMillis() / 1000);
        List<Position> open = repo.openPositions();
        double equity = state.cash();
        for (Position p : open) {
            equity += p.units() * stocks.get(p.stockId()).price();
        }
        final double eq = equity;
        List<Holding> holdings = open.stream().map(p -> {
            StockRow s = stocks.get(p.stockId());
            double value = p.units() * s.price();
            return new Holding(p, s, s.price(), value, value / eq, Fees.netReturn(p.entryPrice(), s.price()));
        }).toList();
        return new Snapshot(equity, state.cash(), holdings, state.inceptionTs());
    }

    /** Equal-weight buy-and-hold of every stock since the model portfolio started, after the sell fee. */
    public double benchmarkSinceInception(long inceptionTs) {
        double sum = 0;
        int n = 0;
        for (StockRow s : repo.stocks()) {
            var start = repo.closeAtOrBefore(s.id(), inceptionTs);
            if (start.isPresent() && start.get() > 0) {
                sum += Fees.netReturn(start.get(), s.price());
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    public Map<Integer, StockRow> stocksById() {
        return repo.stocks().stream().collect(Collectors.toMap(StockRow::id, Function.identity()));
    }
}
