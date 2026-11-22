package com.familyx.stocks.torn;

import com.familyx.stocks.config.BotProperties;
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
import java.util.List;

/** Reads live prices from the Torn API v2 with the owner's key. One call returns all stocks. */
@Component
public class TornClient {
    private static final URI STOCKS = URI.create("https://api.torn.com/v2/torn/stocks");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String apiKey;

    public TornClient(ObjectMapper mapper, BotProperties props) {
        this.mapper = mapper;
        this.apiKey = props.torn().apiKey();
    }

    public List<StockQuote> fetchStocks() throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TORN_API_KEY is not set");
        }
        HttpRequest req = HttpRequest.newBuilder(STOCKS)
                .timeout(Duration.ofSeconds(20))
                .header("accept", "application/json")
                .header("Authorization", "ApiKey " + apiKey)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Torn API returned HTTP " + res.statusCode());
        }
        JsonNode root = mapper.readTree(res.body());
        // Torn reports errors (bad key, rate limit) as HTTP 200 with an "error" object.
        if (root.has("error")) {
            JsonNode e = root.get("error");
            throw new IOException("Torn API error " + e.path("code").asInt() + ": " + e.path("error").asText());
        }
        List<StockQuote> out = new ArrayList<>();
        for (JsonNode s : root.path("stocks")) {
            out.add(new StockQuote(s.path("id").asInt(), s.path("acronym").asText(), s.path("name").asText(),
                    s.path("market").path("price").asDouble()));
        }
        return out;
    }
}
