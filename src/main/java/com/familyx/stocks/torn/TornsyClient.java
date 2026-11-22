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

    /** Hourly bars newer than {@code sinceTs}, oldest first. */
    public List<Bar> hourlyBars(String acronym, long sinceTs) throws IOException, InterruptedException {
        List<Bar> all = new ArrayList<>();
        Long to = null;
        for (int page = 0; page < 10; page++) {
            String url = String.format(Locale.ROOT, "https://tornsy.com/api/%s?interval=h1&limit=%d&from=%d%s",
                    acronym.toLowerCase(Locale.ROOT), PAGE, sinceTs, to == null ? "" : "&to=" + to);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("Tornsy returned HTTP " + res.statusCode() + " for " + acronym);
            }
            JsonNode data = mapper.readTree(res.body()).path("data");
            long oldest = Long.MAX_VALUE;
            int rows = 0;
            for (JsonNode row : data) {
                // [timestamp, open, high, low, close, total_shares]; prices arrive as strings.
                long ts = row.get(0).asLong();
                all.add(new Bar(ts / 3600 * 3600, num(row.get(1)), num(row.get(2)), num(row.get(3)), num(row.get(4))));
                oldest = Math.min(oldest, ts);
                rows++;
            }
            if (rows < PAGE || oldest <= sinceTs) {
                break;
            }
            to = oldest;
            Thread.sleep(500); // be polite to a free community service
        }
        all.sort(Comparator.comparingLong(Bar::ts));
        return all;
    }

    private static double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : Double.parseDouble(n.asText());
    }
}
