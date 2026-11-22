package com.familyx.stocks.torn;

import com.familyx.stocks.engine.Bar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pulls archived hourly bars from tornsy.com (no key needed). Torn's own API only returns the last hour, so this
 * is the only way to backtest from day one. Used once per stock at startup, paging backwards in 2000-hour pages.
 */
@Component
public class TornsyClient {
    private static final int PAGE = 2000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;

    public TornsyClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Hours per request: below Tornsy's 2000-row limit, so a window always comes back whole. */
    public static final long WINDOW_SECONDS = 1900L * 3600;

    /**
     * Hourly bars in {@code [fromTs, toTs)}, oldest first. Callers keep each window to {@link #WINDOW_SECONDS} so
     * the result never depends on which end of a longer range Tornsy would cut off.
     */
    public List<Bar> hourlyBars(String acronym, long fromTs, long toTs) throws IOException, InterruptedException {
        String url = String.format(Locale.ROOT, "https://tornsy.com/api/%s?interval=h1&limit=%d&from=%d&to=%d",
                acronym.toLowerCase(Locale.ROOT), PAGE, fromTs, toTs);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Tornsy returned HTTP " + res.statusCode() + " for " + acronym);
        }
        List<Bar> bars = new ArrayList<>();
        for (JsonNode row : mapper.readTree(res.body()).path("data")) {
            // [timestamp, open, high, low, close, total_shares]; prices arrive as strings.
            long ts = row.get(0).asLong() / 3600 * 3600;
            if (ts >= fromTs && ts < toTs) {
                bars.add(new Bar(ts, num(row.get(1)), num(row.get(2)), num(row.get(3)), num(row.get(4))));
            }
        }
        bars.sort(Comparator.comparingLong(Bar::ts));
        return bars;
    }

    private static double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : Double.parseDouble(n.asText());
    }
}
