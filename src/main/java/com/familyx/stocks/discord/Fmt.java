package com.familyx.stocks.discord;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Number formatting and budget parsing for Discord output. */
public final class Fmt {
    private static final Pattern BUDGET = Pattern.compile("^\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)$",
            Pattern.CASE_INSENSITIVE);

    private Fmt() {
    }

    public static String price(double p) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "$" + f.format(p);
    }

    public static String money(double m) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(Math.round(m));
    }

    public static String shares(double s) {
        return NumberFormat.getIntegerInstance(Locale.US).format((long) Math.floor(s));
    }

    /** Signed percentage, e.g. +2.42% or −3.00%. */
    public static String pct(double fraction) {
        double v = fraction * 100;
        String s = String.format(Locale.US, "%.2f%%", Math.abs(v));
        return (v >= 0 ? "+" : "−") + s;
    }

    /** Unsigned percentage for allocations and win rates. */
    public static String share(double fraction) {
        return String.format(Locale.US, "%.0f%%", fraction * 100);
    }

    public static String duration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return m + "m";
    }

    /** Parses budgets like {@code 2b}, {@code 500m}, {@code 1.5k}, {@code $2,000,000}. */
    public static double parseBudget(String raw) {
        Matcher m = BUDGET.matcher(raw.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Couldn't read that budget. Try 500m, 2b or 1,500,000.");
        }
        BigDecimal base = new BigDecimal(m.group(1).replace(",", ""));
        long mult = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            case "t" -> 1_000_000_000_000L;
            default -> 1L;
        };
        return base.multiply(BigDecimal.valueOf(mult)).doubleValue();
    }
}
