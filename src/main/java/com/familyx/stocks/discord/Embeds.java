package com.familyx.stocks.discord;

import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.engine.BacktestResult;
import com.familyx.stocks.engine.ExitReason;
import com.familyx.stocks.engine.Strategies;
import com.familyx.stocks.engine.Strategy;
import com.familyx.stocks.service.PortfolioService.Holding;
import com.familyx.stocks.service.PortfolioService.Snapshot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds every message the bot posts, matching the agreed mockup. */
public final class Embeds {
    static final Color BUY = new Color(0x3BA55D);
    static final Color SELL = new Color(0xED4245);
    static final Color INFO = new Color(0x5865F2);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneOffset.UTC);

    private Embeds() {
    }

    static String strategyName(String id) {
        return Strategies.byId(id).map(Strategy::displayName).orElse(id);
    }

    public static MessageEmbed buy(Position p, StockRow s, Assignment ev) {
        EmbedBuilder e = new EmbedBuilder().setColor(BUY)
                .setTitle("Buy " + s.acronym() + " · " + s.name())
                .addField("Entry", Fmt.price(p.entryPrice()), true)
                .addField("Allocation", Fmt.share(p.alloc()) + " of bankroll", true)
                .addField("Strategy", strategyName(p.strategyId()), true);
        if (p.target() != null) {
            e.addField("Target", Fmt.price(p.target()) + " (" + Fmt.pct(p.target() / p.entryPrice() - 1) + ")", true);
        }
        if (p.stop() != null) {
            e.addField("Stop", Fmt.price(p.stop()) + " (" + Fmt.pct(p.stop() / p.entryPrice() - 1) + ")", true);
        }
        if (p.trailPct() != null) {
            e.addField("Trailing stop", Fmt.share(p.trailPct()) + " below the high", true);
        }
        e.addField("Max hold", Fmt.duration(p.maxHoldUntil() - p.entryTs()), true);
        String why = "Why: " + p.reason() + ".";
        if (ev != null) {
            why += String.format(Locale.US, " Backtest on %s: %d trades, %s won, %s average after fee.",
                    s.acronym(), ev.isTrades(), Fmt.share(ev.isWinRate()), Fmt.pct(ev.isAvgNet()));
        }
        e.setDescription(why);
        e.setFooter("Position #" + p.id() + " · use Size for my budget on the control panel for share counts");
        return e.build();
    }

    public static MessageEmbed sell(Position p, StockRow s) {
        ExitReason reason = ExitReason.valueOf(p.exitReason());
        return new EmbedBuilder().setColor(SELL)
                .setTitle("Sell " + s.acronym() + " · " + reason.label())
                .addField("Exit", Fmt.price(p.exitPrice()), true)
                .addField("Result", Fmt.pct(p.netReturn()) + " after fee", true)
                .addField("Held", Fmt.duration(p.exitTs() - p.entryTs()), true)
                .setFooter("Position #" + p.id() + " closed · sell fee 0.1% included")
                .build();
    }

    public static MessageEmbed signals(Snapshot snap) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO).setTitle("Open positions");
        if (snap.holdings().isEmpty()) {
            e.setDescription("No open positions. The bot is waiting for a setup that beats the fee.");
            return e.build();
        }
        StringBuilder sb = new StringBuilder("```\n");
        sb.append(String.format("%-4s %-8s %10s %10s %8s%n", "#", "Stock", "Entry", "Now", "P/L"));
        for (Holding h : snap.holdings()) {
            sb.append(String.format(Locale.US, "%-4d %-8s %10s %10s %8s%n", h.position().id(), h.stock().acronym(),
                    Fmt.price(h.position().entryPrice()), Fmt.price(h.price()), Fmt.pct(h.netIfSoldNow())));
        }
        sb.append("```");
        e.setDescription(sb.toString());
        e.setFooter("P/L includes the 0.1% sell fee");
        return e.build();
    }

    public static MessageEmbed size(Snapshot snap, double budget) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO).setTitle("Model portfolio for " + Fmt.money(budget));
        StringBuilder sb = new StringBuilder("```\n");
        sb.append(String.format("%-6s %6s %14s %10s %8s%n", "Stock", "Alloc", "Shares", "Entry", "Now"));
        for (Holding h : snap.holdings()) {
            double shares = budget * h.weight() / h.price();
            sb.append(String.format(Locale.US, "%-6s %6s %14s %10s %8s%n", h.stock().acronym(), Fmt.share(h.weight()),
                    Fmt.shares(shares), Fmt.price(h.position().entryPrice()), Fmt.pct(h.netIfSoldNow())));
        }
        sb.append(String.format("%-6s %6s %14s %10s %8s%n", "Cash", Fmt.share(snap.cashWeight()), "—", "—", "—"));
        sb.append("```");
        e.setDescription(sb.toString());
        e.setFooter("Positions opened over a day ago may already be close to their exit");
        return e.build();
    }

    public static MessageEmbed performance(Snapshot snap, double benchmark, int[] closed, double maxDd,
                                           List<Assignment> assignments, List<Position> recent,
                                           Map<Integer, StockRow> stocks) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO)
                .setTitle("Model portfolio · since " + DAY.format(Instant.ofEpochSecond(snap.inceptionTs())))
                .addField("Return", Fmt.pct(snap.equity() - 1), true)
                .addField("Buy and hold", Fmt.pct(benchmark), true)
                .addField("Trades", closed[0] + " (" + closed[1] + " won)", true)
                .addField("Worst drawdown", maxDd == 0 ? "None yet" : Fmt.pct(-maxDd), true)
                .addField("Cash", Fmt.share(snap.cashWeight()), true)
                .addField("Open", String.valueOf(snap.holdings().size()), true);
        Map<String, Long> byFamily = assignments.stream()
                .collect(Collectors.groupingBy(a -> strategyName(a.strategyId()), Collectors.counting()));
        String live = byFamily.isEmpty() ? "No stock has a strategy that beats the fee yet."
                : "Live strategies: " + byFamily.entrySet().stream()
                .map(en -> en.getKey().toLowerCase(Locale.ROOT) + " on " + en.getValue())
                .collect(Collectors.joining(", ")) + ". "
                + (stocks.size() - assignments.size()) + " stocks have no strategy that beats the fee.";
        StringBuilder sb = new StringBuilder(live);
        if (!recent.isEmpty()) {
            sb.append("\n\nRecent trades:\n```\n");
            for (Position p : recent) {
                sb.append(String.format(Locale.US, "%-6s %8s  %s%n", stocks.get(p.stockId()).acronym(),
                        Fmt.pct(p.netReturn()), ExitReason.valueOf(p.exitReason()).label()));
            }
            sb.append("```");
        }
        e.setDescription(sb.toString());
        return e.build();
    }

    public static MessageEmbed stock(StockRow s, Double ch1h, Double ch24h, Double ch7d, double z,
                                     Assignment a, Holding holding) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO).setTitle(s.acronym() + " · " + s.name())
                .addField("Price", Fmt.price(s.price()), true)
                .addField("1 hour", ch1h == null ? "—" : Fmt.pct(ch1h), true)
                .addField("24 hours", ch24h == null ? "—" : Fmt.pct(ch24h), true)
                .addField("7 days", ch7d == null ? "—" : Fmt.pct(ch7d), true)
                .addField("7-day z-score", Double.isNaN(z) ? "—" : String.format(Locale.US, "%.2f", z), true)
                .addField("Strategy", a == null ? "None" : strategyName(a.strategyId()), true);
        if (a != null) {
            e.setDescription(String.format(Locale.US,
                    "Chosen strategy `%s`: %d backtest trades, %s won, %s average after fee, t-stat %.1f. "
                            + "Out-of-sample check: %d trades, %s.",
                    a.strategyId(), a.isTrades(), Fmt.share(a.isWinRate()), Fmt.pct(a.isAvgNet()), a.isTStat(),
                    a.oosTrades(), Fmt.pct(a.oosTotalReturn())));
        } else {
            e.setDescription("No strategy passed the backtest for this stock, so the bot won't trade it.");
        }
        if (holding != null) {
            e.addField("Open position", "#" + holding.position().id() + " from " + Fmt.price(holding.position().entryPrice())
                    + " (" + Fmt.pct(holding.netIfSoldNow()) + ")", false);
        }
        return e.build();
    }

    public static MessageEmbed strategies(List<Assignment> assignments, Map<Integer, StockRow> stocks) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO).setTitle("Strategy assignments");
        if (assignments.isEmpty()) {
            e.setDescription("No stock has a strategy that passed the backtest yet.");
            return e.build();
        }
        StringBuilder sb = new StringBuilder("```\n");
        sb.append(String.format("%-5s %-16s %4s %5s %7s %5s%n", "Stock", "Strategy", "Trd", "Won", "Avg", "t"));
        assignments.stream().sorted((x, y) -> Double.compare(y.isTotalReturn(), x.isTotalReturn())).forEach(a ->
                sb.append(String.format(Locale.US, "%-5s %-16s %4d %5s %7s %5.1f%n",
                        stocks.get(a.stockId()).acronym(), a.strategyId(), a.isTrades(), Fmt.share(a.isWinRate()),
                        Fmt.pct(a.isAvgNet()), a.isTStat())));
        sb.append("```");
        e.setDescription(sb.toString());
        e.setFooter("Re-selected daily at 00:10 TCT from up to a year of hourly history");
        return e.build();
    }

    public static MessageEmbed backtest(StockRow s, List<BacktestResult> results, int bars) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO)
                .setTitle("Backtest · " + s.acronym() + " · " + Fmt.duration(bars * 3600L) + " of history");
        StringBuilder sb = new StringBuilder("```\n");
        sb.append(String.format("%-16s %4s %5s %7s %8s %6s%n", "Strategy", "Trd", "Won", "Avg", "Total", "MaxDD"));
        for (BacktestResult r : results) {
            sb.append(String.format(Locale.US, "%-16s %4d %5s %7s %8s %6s%n", r.strategyId(), r.tradeCount(),
                    Fmt.share(r.winRate()), Fmt.pct(r.avgNet()), Fmt.pct(r.totalReturn()), Fmt.share(r.maxDrawdown())));
        }
        sb.append("```");
        if (!results.isEmpty()) {
            sb.append("Buy and hold over the same period: ").append(Fmt.pct(results.get(0).buyHold()));
        }
        e.setDescription(sb.toString());
        e.setFooter("All figures after the 0.1% sell fee. Past results don't guarantee future ones.");
        return e.build();
    }

    /** The pinned control panel. {@code alertChannelId} may be blank, in which case the mention is left out. */
    public static MessageEmbed panel(Snapshot snap, int liveStrategies, String alertChannelId) {
        String where = alertChannelId == null || alertChannelId.isBlank() ? "the alerts channel" : "<#" + alertChannelId + ">";
        return new EmbedBuilder().setColor(INFO)
                .setTitle("Family X stock trading")
                .setDescription("The bot runs a model portfolio and posts buy and sell calls in " + where
                        + ". Use the buttons to see where it stands. Replies are only visible to you.")
                .addField("Return", Fmt.pct(snap.equity() - 1), true)
                .addField("Open", snap.holdings().size() + (snap.holdings().size() == 1 ? " position" : " positions"), true)
                .addField("Cash", Fmt.share(snap.cashWeight()), true)
                .addField("Live strategies", liveStrategies + (liveStrategies == 1 ? " stock" : " stocks"), true)
                .setFooter("Updated hourly · all figures after the 0.1% sell fee")
                .setTimestamp(Instant.now())
                .build();
    }

    public static MessageEmbed help() {
        return new EmbedBuilder().setColor(INFO)
                .setTitle("How the Family X stock bot works")
                .setDescription("The bot watches all 35 Torn stocks every minute and tells you when to buy and sell. "
                        + "It can't trade for you: you make the trades in Torn yourself.")
                .addField("The model portfolio",
                        "The bot runs one shared portfolio that everyone copies. Each buy uses 10% of it, so it holds "
                                + "up to 10 stocks at once. Use **Size for my budget** to turn it into share counts for "
                                + "your money, whether that's 50m or 50b.", false)
                .addField("When an alert comes in",
                        "**Buy:** buy the shares Size for my budget gives you, as soon as you can.\n"
                                + "**Sell:** sell all of that stock, whether it's up or down. Sticking to the sells is "
                                + "what keeps losses small.", false)
                .addField("How it picks trades",
                        "Every night it tests three styles on a year of prices: buying dips that usually bounce back, "
                                + "riding steady climbs, and buying breakouts to new highs. A stock only gets traded if "
                                + "one style made money after Torn's 0.1% sell fee, and kept making money on recent "
                                + "prices it wasn't tested on. Most stocks don't pass, and that's on purpose.", false)
                .addField("Good to know",
                        "You can't trade in hospital, in jail or abroad, so check **Open positions** for the current "
                                + "price before acting on an old alert. Past results don't guarantee future ones; only "
                                + "put in money you're happy to have tied up.", false)
                .addField("The buttons",
                        "**Open positions:** what the bot holds now\n"
                                + "**Size for my budget:** your share counts\n"
                                + "**Performance:** results so far\n"
                                + "**Stock lookup:** one stock in detail\n"
                                + "**Strategies:** which stocks it trades and why\n"
                                + "**Backtest:** test strategies on past prices", false)
                .build();
    }

    public static MessageEmbed error(String message) {
        return new EmbedBuilder().setColor(SELL).setDescription(message).build();
    }
}
