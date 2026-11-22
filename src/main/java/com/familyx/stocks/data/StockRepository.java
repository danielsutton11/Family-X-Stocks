package com.familyx.stocks.data;

import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.PortfolioState;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.engine.Bar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class StockRepository {
    private final JdbcTemplate jdbc;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- settings ----------

    public Optional<String> setting(String key) {
        return jdbc.queryForList("select value from bot_setting where key = ?", String.class, key).stream().findFirst();
    }

    public void saveSetting(String key, String value) {
        jdbc.update("insert into bot_setting (key, value) values (?, ?) on conflict (key) do update set value = excluded.value",
                key, value);
    }

    // ---------- stocks and prices ----------

    public void upsertStock(int id, String acronym, String name, double price, long ts) {
        jdbc.update("""
                insert into stock (id, acronym, name, price, updated_ts) values (?, ?, ?, ?, ?)
                on conflict (id) do update set acronym = excluded.acronym, name = excluded.name,
                    price = excluded.price, updated_ts = excluded.updated_ts
                """, id, acronym, name, price, ts);
    }

    public List<StockRow> stocks() {
        return jdbc.query("select id, acronym, name, price, updated_ts from stock order by id",
                (rs, i) -> new StockRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getLong(5)));
    }

    public Optional<StockRow> stockByAcronym(String acronym) {
        return stocks().stream().filter(s -> s.acronym().equalsIgnoreCase(acronym)).findFirst();
    }

    public void insertTick(int stockId, long ts, double price) {
        jdbc.update("insert into price_tick (stock_id, ts, price) values (?, ?, ?) on conflict do nothing",
                stockId, ts, price);
    }

    /** Adds a minute price to the hour's bar, creating the bar on the hour's first tick. */
    public void addToHourBar(int stockId, long hourTs, double price) {
        jdbc.update("""
                insert into bar_hourly (stock_id, ts, open, high, low, close) values (?, ?, ?, ?, ?, ?)
                on conflict (stock_id, ts) do update set high = greatest(bar_hourly.high, excluded.high),
                    low = least(bar_hourly.low, excluded.low), close = excluded.close
                """, stockId, hourTs, price, price, price, price);
    }

    public void insertBarsIfAbsent(int stockId, List<Bar> bars) {
        jdbc.batchUpdate("""
                        insert into bar_hourly (stock_id, ts, open, high, low, close) values (?, ?, ?, ?, ?, ?)
                        on conflict do nothing
                        """, bars, 500, (ps, b) -> {
                    ps.setInt(1, stockId);
                    ps.setLong(2, b.ts());
                    ps.setDouble(3, b.open());
                    ps.setDouble(4, b.high());
                    ps.setDouble(5, b.low());
                    ps.setDouble(6, b.close());
                });
    }

    public long oldestBarTs(int stockId) {
        Long ts = jdbc.queryForObject("select min(ts) from bar_hourly where stock_id = ?", Long.class, stockId);
        return ts == null ? Long.MAX_VALUE : ts;
    }

    /** The latest {@code limit} completed bars before {@code beforeTs}, oldest first. */
    public List<Bar> bars(int stockId, long beforeTs, int limit) {
        List<Bar> bars = new ArrayList<>(jdbc.query("""
                select ts, open, high, low, close from bar_hourly where stock_id = ? and ts < ?
                order by ts desc limit ?
                """, BAR, stockId, beforeTs, limit));
        Collections.reverse(bars);
        return bars;
    }

    public Optional<Double> closeAtOrBefore(int stockId, long ts) {
        List<Double> r = jdbc.queryForList(
                "select close from bar_hourly where stock_id = ? and ts <= ? order by ts desc limit 1",
                Double.class, stockId, ts);
        return r.stream().findFirst();
    }

    public int pruneTicks(long beforeTs) {
        return jdbc.update("delete from price_tick where ts < ?", beforeTs);
    }

    private static final RowMapper<Bar> BAR = (rs, i) ->
            new Bar(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5));

    // ---------- strategy assignments ----------

    public void saveAssignment(Assignment a) {
        jdbc.update("""
                insert into strategy_assignment (stock_id, strategy_id, is_trades, is_win_rate, is_avg_net,
                    is_profit_factor, is_total_return, is_t_stat, oos_trades, oos_total_return, selected_ts)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (stock_id) do update set strategy_id = excluded.strategy_id,
                    is_trades = excluded.is_trades, is_win_rate = excluded.is_win_rate,
                    is_avg_net = excluded.is_avg_net, is_profit_factor = excluded.is_profit_factor,
                    is_total_return = excluded.is_total_return, is_t_stat = excluded.is_t_stat,
                    oos_trades = excluded.oos_trades, oos_total_return = excluded.oos_total_return,
                    selected_ts = excluded.selected_ts
                """, a.stockId(), a.strategyId(), a.isTrades(), a.isWinRate(), a.isAvgNet(),
                finite(a.isProfitFactor()), a.isTotalReturn(), finite(a.isTStat()), a.oosTrades(),
                a.oosTotalReturn(), a.selectedTs());
    }

    public void deleteAssignment(int stockId) {
        jdbc.update("delete from strategy_assignment where stock_id = ?", stockId);
    }

    public List<Assignment> assignments() {
        return jdbc.query("select * from strategy_assignment", StockRepository::assignment);
    }

    public Optional<Assignment> assignment(int stockId) {
        return jdbc.query("select * from strategy_assignment where stock_id = ?", StockRepository::assignment, stockId)
                .stream().findFirst();
    }

    private static Assignment assignment(ResultSet rs, int i) throws SQLException {
        return new Assignment(rs.getInt("stock_id"), rs.getString("strategy_id"), rs.getInt("is_trades"),
                rs.getDouble("is_win_rate"), rs.getDouble("is_avg_net"), rs.getDouble("is_profit_factor"),
                rs.getDouble("is_total_return"), rs.getDouble("is_t_stat"), rs.getInt("oos_trades"),
                rs.getDouble("oos_total_return"), rs.getLong("selected_ts"));
    }

    /** Postgres double precision accepts Infinity, but it reads badly in Discord; cap it instead. */
    private static double finite(double d) {
        return Double.isInfinite(d) ? 999 : d;
    }

    // ---------- model portfolio ----------

    public PortfolioState portfolio(long nowTs) {
        jdbc.update("insert into portfolio_state (id, cash, inception_ts) values (1, 1.0, ?) on conflict do nothing", nowTs);
        return jdbc.queryForObject("select cash, inception_ts from portfolio_state where id = 1",
                (rs, i) -> new PortfolioState(rs.getDouble(1), rs.getLong(2)));
    }

    public void setCash(double cash) {
        jdbc.update("update portfolio_state set cash = ? where id = 1", cash);
    }

    public long openPosition(int stockId, String strategyId, long ts, double price, double units, double alloc,
                             Double target, Double stop, Double trailPct, long maxHoldUntil, String reason) {
        return jdbc.queryForObject("""
                insert into model_position (stock_id, strategy_id, status, entry_ts, entry_price, units, alloc,
                    target, stop, trail_pct, high_water, max_hold_until, reason)
                values (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, stockId, strategyId, ts, price, units, alloc, target, stop, trailPct, price,
                maxHoldUntil, reason);
    }

    public void updateHighWater(long id, double highWater) {
        jdbc.update("update model_position set high_water = ? where id = ?", highWater, id);
    }

    public void closePosition(long id, long ts, double price, String reason, double netReturn) {
        jdbc.update("""
                update model_position set status = 'CLOSED', exit_ts = ?, exit_price = ?, exit_reason = ?,
                    net_return = ? where id = ?
                """, ts, price, reason, netReturn, id);
    }

    public List<Position> openPositions() {
        return jdbc.query("select * from model_position where status = 'OPEN' order by entry_ts",
                StockRepository::position);
    }

    public List<Position> closedPositions(int limit) {
        return jdbc.query("select * from model_position where status = 'CLOSED' order by exit_ts desc limit ?",
                StockRepository::position, limit);
    }

    public int[] closedCounts() {
        return jdbc.queryForObject("""
                select count(*), count(*) filter (where net_return > 0) from model_position where status = 'CLOSED'
                """, (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)});
    }

    private static Position position(ResultSet rs, int i) throws SQLException {
        return new Position(rs.getLong("id"), rs.getInt("stock_id"), rs.getString("strategy_id"),
                rs.getString("status"), rs.getLong("entry_ts"), rs.getDouble("entry_price"), rs.getDouble("units"),
                rs.getDouble("alloc"), rs.getObject("target", Double.class), rs.getObject("stop", Double.class),
                rs.getObject("trail_pct", Double.class), rs.getDouble("high_water"), rs.getLong("max_hold_until"),
                rs.getString("reason"), rs.getObject("exit_ts", Long.class), rs.getObject("exit_price", Double.class),
                rs.getString("exit_reason"), rs.getObject("net_return", Double.class));
    }

    public void snapshotEquity(long ts, double equity) {
        jdbc.update("insert into equity_snapshot (ts, equity) values (?, ?) on conflict (ts) do update set equity = excluded.equity",
                ts, equity);
    }

    public double maxDrawdown() {
        List<Double> eq = jdbc.queryForList("select equity from equity_snapshot order by ts", Double.class);
        double peak = Double.NEGATIVE_INFINITY;
        double dd = 0;
        for (double e : eq) {
            peak = Math.max(peak, e);
            dd = Math.max(dd, (peak - e) / peak);
        }
        return dd;
    }
}
