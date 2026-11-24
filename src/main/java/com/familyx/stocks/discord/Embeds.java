package com.familyx.stocks.discord;

import com.familyx.stocks.data.Models.Assignment;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.engine.BacktestResult;
import com.familyx.stocks.engine.ExitReason;
import com.familyx.stocks.engine.Rotation;
import com.familyx.stocks.engine.Strategies;
import com.familyx.stocks.engine.Strategy;
import com.familyx.stocks.service.ExportService;
import com.familyx.stocks.service.TradeEvents;
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
import java.util.Set;
import java.util.stream.Collectors;

/** Builds every message the bot posts, matching the agreed mockup. */
public final class Embeds {
    static final Color BUY = new Color(0x3BA55D);
    static final Color SELL = new Color(0xED4245);
    static final Color INFO = new Color(0x5865F2);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_DAY = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK).withZone(ZoneOffset.UTC);

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
        return new EmbedBuilder().setColor(SELL)
                .setTitle("Sell " + s.acronym() + " · " + exitLabel(p.exitReason()))
                .setDescription("Sell the " + s.acronym() + " shares you bought on the buy alert for position #" + p.id()
                        + ". Don't sell shares that make up a benefit block.")
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
                                           List<Position> recent, Map<Integer, StockRow> stocks,
                                           Snapshot dip, int[] dipClosed, long nextRebalanceTs) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO)
                .setTitle("Model portfolio · since " + DAY.format(Instant.ofEpochSecond(snap.inceptionTs())))
                .addField("Return", Fmt.pct(snap.equity() - 1), true)
                .addField("Buy and hold", Fmt.pct(benchmark), true)
                .addField("Trades", closed[0] + " (" + closed[1] + " won)", true)
                .addField("Worst drawdown", maxDd == 0 ? "None yet" : Fmt.pct(-maxDd), true)
                .addField("Cash", Fmt.share(snap.cashWeight()), true)
                .addField("Next rebalance", TIME_DAY.format(Instant.ofEpochSecond(nextRebalanceTs)) + " TCT", true);
        StringBuilder sb = new StringBuilder("Strategy: rotation into the stocks furthest below their 4-week average.\n"
                + "Dip strategy (paper, for comparison): " + Fmt.pct(dip.equity() - 1) + " since "
                + DAY.format(Instant.ofEpochSecond(dip.inceptionTs())) + ", " + dipClosed[0] + " trades ("
                + dipClosed[1] + " won).");
        if (!recent.isEmpty()) {
            sb.append("\n\nRecent trades:\n```\n");
            for (Position p : recent) {
                sb.append(String.format(Locale.US, "%-6s %8s  %s%n", stocks.get(p.stockId()).acronym(),
                        Fmt.pct(p.netReturn()), exitLabel(p.exitReason())));
            }
            sb.append("```");
        }
        e.setDescription(sb.toString());
        return e.build();
    }

    static String exitLabel(String reason) {
        try {
            return ExitReason.valueOf(reason).label();
        } catch (IllegalArgumentException | NullPointerException ex) {
            return String.valueOf(reason);
        }
    }

    public static MessageEmbed stock(StockRow s, Double ch1h, Double ch24h, Double ch7d, Rotation.Rank rank,
                                     int ranked, Rotation.Params params, Assignment dip, Holding holding) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO).setTitle(s.acronym() + " · " + s.name())
                .addField("Price", Fmt.price(s.price()), true)
                .addField("1 hour", ch1h == null ? "—" : Fmt.pct(ch1h), true)
                .addField("24 hours", ch24h == null ? "—" : Fmt.pct(ch24h), true)
                .addField("7 days", ch7d == null ? "—" : Fmt.pct(ch7d), true)
                .addField("Vs " + params.lookbackHours() / 168 + "-week average", rank == null ? "—" : Fmt.pct(rank.gap()), true)
                .addField("Rotation rank", rank == null ? "—" : "#" + (rank.rank() + 1) + " of " + ranked, true);
        String status;
        if (rank == null) {
            status = "Not enough history to rank this stock yet.";
        } else if (holding != null) {
            status = rank.rank() < params.keep()
                    ? "Held. It stays while it ranks in the top " + params.keep() + "."
                    : "Held, but now outside the top " + params.keep() + ", so it will be sold at the next rebalance.";
        } else if (rank.rank() < params.hold()) {
            status = "In the top " + params.hold() + ", so it's a buy candidate at the next rebalance if a slot is free.";
        } else {
            status = "Not held. The bot buys from the top " + params.hold() + " at each rebalance.";
        }
        e.setDescription(status + (dip == null ? "" : "\nDip strategy (paper) trades it with `" + dip.strategyId() + "`."));
        if (holding != null) {
            e.addField("Open position", "#" + holding.position().id() + " from " + Fmt.price(holding.position().entryPrice())
                    + " (" + Fmt.pct(holding.netIfSoldNow()) + ")", false);
        }
        return e.build();
    }

    public static MessageEmbed rankings(List<Rotation.Rank> ranking, Map<Integer, StockRow> stocks, Set<Integer> held,
                                        Rotation.Params params, long nextRebalanceTs) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO)
                .setTitle("Rankings vs " + params.lookbackHours() / 168 + "-week average");
        StringBuilder sb = new StringBuilder("```\n");
        for (Rotation.Rank r : ranking) {
            String note = held.contains(r.stockId()) ? (r.rank() < params.keep() ? "held" : "held · sell next")
                    : (r.rank() < params.hold() ? "buy candidate" : "");
            sb.append(String.format(Locale.US, "#%-3d %-5s %7s  %s%n", r.rank() + 1, stocks.get(r.stockId()).acronym(),
                    Fmt.pct(r.gap()), note));
            if (r.rank() == params.keep() - 1) {
                sb.append("---- sell line: held stocks below here are sold ----\n");
            }
        }
        sb.append("```");
        e.setDescription(sb.toString());
        e.setFooter("Next rebalance " + TIME_DAY.format(Instant.ofEpochSecond(nextRebalanceTs)) + " TCT · holds the top "
                + params.hold() + ", sells holdings that fall below #" + params.keep());
        return e.build();
    }

    /** One message per rebalance: what to sell, what to buy, what to keep. */
    public static MessageEmbed rebalance(TradeEvents.Rebalanced r) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO)
                .setTitle("Rebalance · " + TIME_DAY.format(Instant.ofEpochSecond(r.ts())) + " TCT");
        if (!r.changed()) {
            e.setDescription("No changes: keep everything you hold.");
        } else {
            e.setDescription("Sell first, then buy. Use **Size for my budget** for share counts. "
                    + "Fine to act up to 12 hours late.");
        }
        StringBuilder sell = new StringBuilder();
        for (TradeEvents.Sold x : r.sold()) {
            sell.append(x.stock().acronym()).append(" · ").append(Fmt.pct(x.closed().netReturn())).append(", ")
                    .append(Fmt.duration(x.closed().exitTs() - x.closed().entryTs())).append("\n");
        }
        StringBuilder buy = new StringBuilder();
        for (TradeEvents.Bought x : r.bought()) {
            buy.append(x.stock().acronym()).append(" · ").append(Fmt.share(x.position().alloc())).append(" at ")
                    .append(Fmt.price(x.position().entryPrice())).append("\n");
        }
        StringBuilder keep = new StringBuilder();
        for (TradeEvents.Kept x : r.kept()) {
            keep.append(x.stock().acronym()).append(" · ").append(Fmt.pct(x.netIfSoldNow())).append("\n");
        }
        e.addField("Sell (" + r.sold().size() + ")", sell.isEmpty() ? "—" : sell.toString(), true);
        e.addField("Buy (" + r.bought().size() + ")", buy.isEmpty() ? "—" : buy.toString(), true);
        e.addField("Keep (" + r.kept().size() + ")", keep.isEmpty() ? "—" : keep.toString(), true);
        e.setFooter("Next rebalance " + TIME_DAY.format(Instant.ofEpochSecond(r.nextTs()))
                + " TCT · never sell benefit-block shares · results after 0.1% sell fee");
        return e.build();
    }

    /** An older rebalance alert, greyed out once a newer one replaces it. */
    public static MessageEmbed superseded(MessageEmbed original, long newTs) {
        return new EmbedBuilder(original).setColor(CLOSED)
                .setTitle("Superseded · " + original.getTitle())
                .setDescription("Replaced by the rebalance on " + TIME_DAY.format(Instant.ofEpochSecond(newTs))
                        + " TCT. Don't act on this one.")
                .build();
    }

    public static MessageEmbed backtest(StockRow s, List<BacktestResult> results, int bars) {
        EmbedBuilder e = new EmbedBuilder().setColor(INFO)
                .setTitle("Dip backtest · " + s.acronym() + " · " + Fmt.duration(bars * 3600L) + " of history");
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
    public static MessageEmbed panel(Snapshot snap, Rotation.Params params, long nextRebalanceTs, String alertChannelId) {
        String where = alertChannelId == null || alertChannelId.isBlank() ? "the alerts channel" : "<#" + alertChannelId + ">";
        return new EmbedBuilder().setColor(INFO)
                .setTitle("Family X stock trading")
                .setDescription("Every " + params.everyHours() / 24 + " days the bot holds the " + params.hold()
                        + " stocks furthest below their " + params.lookbackHours() / 168 + "-week average and posts the "
                        + "trades in " + where + ". Use the buttons to see where it stands. Replies are only visible to you.")
                .addField("Return", Fmt.pct(snap.equity() - 1), true)
                .addField("Holding", snap.holdings().size() + (snap.holdings().size() == 1 ? " stock" : " stocks"), true)
                .addField("Cash", Fmt.share(snap.cashWeight()), true)
                .addField("Next rebalance", TIME_DAY.format(Instant.ofEpochSecond(nextRebalanceTs)) + " TCT", true)
                .setFooter("Updated hourly · all figures after the 0.1% sell fee")
                .setTimestamp(Instant.now())
                .build();
    }

    public static MessageEmbed help() {
        return new EmbedBuilder().setColor(INFO)
                .setTitle("How the Family X stock bot works")
                .setDescription("The bot watches all 35 Torn stocks every minute and tells you what to hold. "
                        + "It can't trade for you: you make the trades in Torn yourself.")
                .addField("The strategy",
                        "Torn stocks tend to drift back towards their recent average. Every 3 days the bot ranks all "
                                + "35 stocks by how far they sit below their 4-week average and holds the 5 cheapest, "
                                + "20% each. A stock is only sold once it drops out of the 10 cheapest, so holdings "
                                + "aren't swapped over tiny changes. On a year of past prices this beat buy and hold in "
                                + "every quarter, but past results don't guarantee future ones.", false)
                .addField("When a rebalance comes in",
                        "**Sell** the stocks listed under Sell first, then **buy** the ones under Buy. "
                                + "**Size for my budget** gives you share counts for your money, whether that's 50m or 50b. "
                                + "You don't need to be quick: acting up to 12 hours late made almost no difference in testing. "
                                + "Older rebalance alerts are greyed out as **Superseded**, so always follow the newest one.", false)
                .addField("Benefit blocks",
                        "The bot ignores dividends. Leave your blocks out of your budget and never sell block shares "
                                + "when a stock is listed under Sell.", false)
                .addField("Good to know",
                        "You can't trade in hospital, in jail or abroad. Only put in money you're happy to have tied up.", false)
                .addField("The buttons",
                        "**Open positions:** what the bot holds now\n"
                                + "**Size for my budget:** your share counts\n"
                                + "**Performance:** results so far, against buy and hold\n"
                                + "**Stock lookup:** one stock's price, rank and status\n"
                                + "**Rankings:** all 35 stocks ranked, with the sell line\n"
                                + "**Backtest:** test the older dip strategies on past prices\n"
                                + "**Export data:** bot owner only, for tuning the settings", false)
                .build();
    }

    public static MessageEmbed exportReady(ExportService.Export ex) {
        return new EmbedBuilder().setColor(INFO)
                .setTitle("Export ready")
                .setDescription("Download both files and upload them to Claude.\n\n"
                        + "Settings and selection rules: current\n"
                        + "Trades (open and closed): " + ex.trades() + "\n"
                        + "Hourly equity curve: " + ex.equityPoints() + " points\n"
                        + "Strategy backtests: " + ex.stocks() + " stocks × " + ex.strategies() + "\n"
                        + "Hourly price bars: " + String.format(Locale.US, "%,d", ex.barRows()) + " rows")
                .setFooter("Backtests include why each rejected strategy failed, so near-misses are visible")
                .build();
    }

    static final Color CLOSED = new Color(0x80848E);
    static final Color WARN = new Color(0xFAA61A);

    /** The original buy alert, greyed out with the result appended, so nobody acts on a finished trade. */
    public static MessageEmbed buyClosed(MessageEmbed original, Position p) {
        return new EmbedBuilder(original).setColor(CLOSED)
                .setTitle("Closed · " + original.getTitle())
                .addField("Result", Fmt.pct(p.netReturn()) + " after fee · " + exitLabel(p.exitReason()) + " after "
                        + Fmt.duration(p.exitTs() - p.entryTs()) + " · don't act on this alert", false)
                .build();
    }

    public static MessageEmbed collectionDown(String error, long sinceTs) {
        return new EmbedBuilder().setColor(WARN)
                .setTitle("Price collection has stopped")
                .setDescription("No prices for 15 minutes, so the bot can't trade or close positions. Last error: "
                        + error + ".")
                .setFooter("Failing since " + TIME.format(Instant.ofEpochSecond(sinceTs))
                        + " TCT · you'll get one more message when it recovers")
                .build();
    }

    public static MessageEmbed collectionUp(long sinceTs, long nowTs) {
        return new EmbedBuilder().setColor(BUY)
                .setTitle("Price collection is back")
                .setDescription("Prices are coming in again after " + Fmt.duration(nowTs - sinceTs)
                        + ". Stops and targets are being checked as normal.")
                .build();
    }

    public static MessageEmbed error(String message) {
        return new EmbedBuilder().setColor(SELL).setDescription(message).build();
    }
}
