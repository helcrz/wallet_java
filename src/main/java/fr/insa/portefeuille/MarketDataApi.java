package fr.insa.portefeuille;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.apache.commons.csv.CSVFormat;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class MarketDataApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String,String> EXAMPLES = Map.of("IBM","IBM", "MSFT","Microsoft",
            "AAPL","Apple", "SPY","SPDR S&P 500", "TLT","iShares 20+ Year Treasury Bond");
    private final String key;
    private final String alpacaSecret;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final URI endpoint;
    private final URI assetsEndpoint;
    private final HttpClient http;
    private final Map<String,Series> cache = new HashMap<>();

    private final Map<String,LocalDate> fetchedOn = new HashMap<>();
    private Map<String,Instrument> instruments = Map.of();
    private LocalDate catalogDate;
    public record Instrument(String symbol, String name, String exchange) {}
    private static final java.util.regex.Pattern TICKER = java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9.:-]{0,23}");

    public record LiveTrade(BigDecimal price, Instant time) {
        public LiveTrade {
            AssetPosition.positive(price, "Cours IEX");
            Objects.requireNonNull(time);
        }
    }
    public record Series(String symbol, String name, String type, NavigableMap<LocalDate,BigDecimal> prices, LiveTrade lastTrade) {
        public Series(String symbol, String name, String type, NavigableMap<LocalDate,BigDecimal> prices) {
            this(symbol,name,type,prices,null);
        }
        public Series {
            prices = Collections.unmodifiableNavigableMap(new TreeMap<>(prices));
            if (prices.isEmpty()) throw new IllegalArgumentException("Historique vide pour " + symbol);
            prices.values().forEach(p -> AssetPosition.positive(p, "Clôture"));
        }
        public LocalDate date() { return lastTrade == null ? prices.lastKey() : lastTrade.time().atZone(NEW_YORK).toLocalDate(); }
        public BigDecimal price() { return lastTrade == null ? prices.lastEntry().getValue() : lastTrade.price(); }
        public String priceStamp() { return lastTrade == null ? date().toString() : lastTrade.time().toString(); }
    }

    public MarketDataApi(String key) { this(key, URI.create("https://www.alphavantage.co/query")); }
    MarketDataApi(String key, URI endpoint) {
        this(key, endpoint, null);
    }
    MarketDataApi(String key, URI endpoint, String alpacaSecret) {
        this(key, endpoint, alpacaSecret, endpoint.resolve("/v2/assets"));
    }
    private MarketDataApi(String key, URI endpoint, String alpacaSecret, URI assetsEndpoint) {
        this.key = key == null ? "" : key.trim();
        this.alpacaSecret = alpacaSecret;
        this.endpoint = endpoint;
        this.assetsEndpoint = assetsEndpoint;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }
    static MarketDataApi configured() {
        String id = System.getenv().getOrDefault("APCA_API_KEY_ID","").strip();
        String secret = System.getenv().getOrDefault("APCA_API_SECRET_KEY","").strip();
        if (id.isEmpty() != secret.isEmpty()) throw new IllegalArgumentException("Alpaca requiert APCA_API_KEY_ID et APCA_API_SECRET_KEY.");
        if (!id.isEmpty()) {
            String host = id.startsWith("AK") ? "api.alpaca.markets" : "paper-api.alpaca.markets";
            return new MarketDataApi(id, URI.create("https://data.alpaca.markets/v2/stocks/"), secret,
                    URI.create("https://" + host + "/v2/assets"));
        }
        return new MarketDataApi(System.getenv("ALPHAVANTAGE_API_KEY"));
    }
    public boolean live() { return alpacaSecret != null; }
    public boolean demo() { return key.isEmpty(); }
    static String symbol(String text) {
        String s = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        if (!TICKER.matcher(s).matches()) throw new IllegalArgumentException("Symbole boursier invalide.");
        return s;
    }
    public List<Instrument> catalog() throws IOException {
        LocalDate today = LocalDate.now(NEW_YORK);
        if (!today.equals(catalogDate)) {
            var found = new TreeMap<String,Instrument>();
            if (demo()) EXAMPLES.forEach((s,n) -> found.put(s,new Instrument(s,n,"Démo")));
            else if (live()) {
                var uri = URI.create(assetsEndpoint + "?status=active&asset_class=us_equity");
                var data = JSON.readTree(responseBody(alpacaRequest(uri),"Alpaca"));
                if (data == null || !data.isArray()) throw new IOException("Catalogue Alpaca indisponible.");
                for (var asset : data) {
                    if (asset.path("tradable").asBoolean() && asset.path("status").asText().equals("active")
                            && asset.path("class").asText().equals("us_equity") && !asset.path("exchange").asText().equals("OTC"))
                        addInstrument(found,asset.path("symbol").asText(),asset.path("name").asText(),asset.path("exchange").asText());
                }
            } else {
                String body = responseBody(alphaRequest("LISTING_STATUS&state=active"),"Alpha Vantage");
                var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
                try (var records = format.parse(new java.io.StringReader(body))) {
                    if (!records.getHeaderMap().keySet().containsAll(List.of("symbol","name","exchange","assetType","status")))
                        throw new IOException("Catalogue Alpha Vantage indisponible. Vérifie la clé et le quota.");
                    for (var row : records) {
                        if (row.isConsistent() && row.get("status").equalsIgnoreCase("Active")
                                && Set.of("Stock","ETF").contains(row.get("assetType")))
                            addInstrument(found,row.get("symbol"),row.get("name"),row.get("exchange"));
                    }
                }
            }
            if (found.isEmpty()) throw new IOException("Le fournisseur n’a renvoyé aucun titre disponible.");
            instruments = found;
            catalogDate = today;
        }
        return List.copyOf(instruments.values());
    }
    private static void addInstrument(Map<String,Instrument> found,String symbol,String name,String exchange) {
        if (TICKER.matcher(symbol).matches() && !name.isBlank())
            found.put(symbol,new Instrument(symbol,name,exchange));
    }
    public Series load(String input, boolean refresh) throws IOException {
        String ticker = symbol(input);
        Series previous = cache.get(ticker);
        if (live()) return loadIex(ticker, previous);
        if (!refresh && previous != null && LocalDate.now().equals(fetchedOn.get(ticker))) return previous;
        Series next;
        if (demo()) next = example(ticker);
        else {
            String name, type;
            if (previous != null) {
                name = previous.name(); type = previous.type();
            } else {
                JsonNode matches = request("SYMBOL_SEARCH&keywords=" + ticker).path("bestMatches");
                JsonNode match = null;
                for (JsonNode candidate : matches)
                    if (ticker.equalsIgnoreCase(candidate.path("1. symbol").asText())) { match = candidate; break; }
                if (match == null) throw new IOException("Symbole introuvable : " + ticker);
                if (!"USD".equals(match.path("8. currency").asText()))
                    throw new IOException("Ce portefeuille accepte uniquement les cotations en USD.");
                name = match.path("2. name").asText(ticker);
                type = switch (match.path("3. type").asText()) {
                    case "Equity" -> "Action";
                    case "ETF" -> "ETF";
                    default -> throw new IOException("Type non pris en charge. Choisis une action ou un ETF.");
                };
            }
            JsonNode result = request("TIME_SERIES_DAILY&outputsize=compact&symbol=" + ticker);
            next = parse(ticker, name, type, result);
        }
        cache.put(ticker, next);
        fetchedOn.put(ticker, LocalDate.now());
        return next;
    }

    private Series loadIex(String ticker, Series history) throws IOException {
        LocalDate today = LocalDate.now(NEW_YORK);
        if (history == null || !today.equals(fetchedOn.get(ticker))) {
            Instant midnight = today.atStartOfDay(NEW_YORK).toInstant();
            String range = "&start=" + today.minusMonths(8) + "&end=" + midnight.minusNanos(1);
            JsonNode data = iexRequest(ticker, "bars?feed=iex&timeframe=1Day&sort=desc&limit=100" + range);
            var prices = new TreeMap<LocalDate,BigDecimal>();
            try {
                for (JsonNode bar : data.path("bars")) {
                    LocalDate day = Instant.parse(bar.path("t").asText()).atZone(NEW_YORK).toLocalDate();
                    if (day.isBefore(today)) prices.put(day, new BigDecimal(bar.path("c").asText()));
                }
                String type = switch (ticker) {
                    case "SPY","TLT" -> "ETF";
                    case "AAPL","IBM","MSFT" -> "Action";
                    default -> "Titre US";
                };
                history = new Series(ticker, EXAMPLES.getOrDefault(ticker,ticker), type, prices);
            } catch (RuntimeException e) { throw new IOException("Historique IEX indisponible ou invalide pour " + ticker); }
            cache.put(ticker, history);
            fetchedOn.put(ticker, today);
        }
        JsonNode trade = iexRequest(ticker, "trades/latest?feed=iex").path("trade");
        try {
            var last = new LiveTrade(new BigDecimal(trade.path("p").asText()), Instant.parse(trade.path("t").asText()));
            var instrument = instruments.get(ticker);
            return new Series(ticker, instrument == null ? history.name() : instrument.name(), history.type(), history.prices(), last);
        } catch (RuntimeException e) { throw new IOException("Dernière transaction IEX absente ou invalide pour " + ticker); }
    }
    private JsonNode iexRequest(String ticker, String resource) throws IOException {
        var request = alpacaRequest(URI.create(endpoint.toString() + ticker + "/" + resource));
        var data = send(request, "Alpaca IEX");
        if (!ticker.equals(data.path("symbol").asText()) || !"USD".equals(data.path("currency").asText("USD")))
            throw new IOException("Symbole ou devise incohérents dans la réponse IEX.");
        return data;
    }
    private HttpRequest alpacaRequest(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("APCA-API-KEY-ID",key)
                .header("APCA-API-SECRET-KEY",alpacaSecret).GET().build();
    }
    private HttpRequest alphaRequest(String query) {
        URI uri = URI.create(endpoint + "?function=" + query + "&apikey=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
    }
    private JsonNode request(String query) throws IOException {
        var data = send(alphaRequest(query), "Alpha Vantage");
        if (data.has("Note") || data.has("Information"))
            throw new IOException("Alpha Vantage a limité l’accès. Vérifie la clé, le quota et les droits de l’abonnement.");
        if (data.has("Error Message")) throw new IOException("Alpha Vantage a rejeté le symbole ou la requête.");
        return data;
    }
    private JsonNode send(HttpRequest request, String source) throws IOException {
        String body = responseBody(request,source);
        JsonNode data;
        try { data = JSON.readTree(body); }
        catch (IOException e) { throw new IOException("Réponse JSON illisible."); }
        if (data == null || !data.isObject()) throw new IOException("Réponse vide ou invalide de " + source);
        return data;
    }
    private String responseBody(HttpRequest request, String source) throws IOException {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException(source + " : HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête interrompue.");
        } catch (HttpTimeoutException e) { throw new IOException(source + " ne répond pas dans le délai prévu."); }
    }

    static Series parse(String symbol, String name, String type, JsonNode data) throws IOException {
        if (!symbol.equalsIgnoreCase(data.path("Meta Data").path("2. Symbol").asText()))
            throw new IOException("Symbole absent ou incohérent dans la réponse de cours.");
        var prices = new TreeMap<LocalDate,BigDecimal>();
        try {
            data.path("Time Series (Daily)").fields().forEachRemaining(entry ->
                prices.put(LocalDate.parse(entry.getKey()), new BigDecimal(entry.getValue().path("4. close").asText())));
            return new Series(symbol, name, type, prices);
        } catch (RuntimeException e) { throw new IOException("Historique vide, date ou cours invalide pour " + symbol); }
    }

    private static Series example(String ticker) {
        String name = EXAMPLES.get(ticker);
        if (name == null) throw new IllegalArgumentException("Symboles de démonstration : IBM, MSFT, AAPL, SPY, TLT.");
        var prices = new TreeMap<LocalDate,BigDecimal>();
        var common = new Random(917);
        var specific = new Random(ticker.hashCode());
        double price = 80 + specific.nextInt(90);
        LocalDate date = LocalDate.of(2025, 1, 6);
        while (prices.size() < 100) {
            if (date.getDayOfWeek().getValue() <= 5) {
                double market = common.nextGaussian() * .008;
                price *= 1 + .0004 + (ticker.equals("TLT") ? -.3 : 1) * market + specific.nextGaussian() * .006;
                prices.put(date, BigDecimal.valueOf(Math.round(price * 100), 2));
            }
            date = date.plusDays(1);
        }
        return new Series(ticker, name, Set.of("SPY","TLT").contains(ticker) ? "ETF" : "Action", prices);
    }
}
