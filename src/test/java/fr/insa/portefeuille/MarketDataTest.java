package fr.insa.portefeuille;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.insa.portefeuille.PortfolioTest.*;

class MarketDataTest {
    HttpServer server;
    MarketDataApi api;
    AtomicInteger requests = new AtomicInteger();
    String currency = "USD", history = """
        {"Meta Data":{"2. Symbol":"IBM"},"Time Series (Daily)":{
          "2025-01-07":{"4. close":"110"},"2025-01-06":{"4. close":"100"}}}
        """;
    int status = 200;
    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/query", e -> {
            requests.incrementAndGet();
            String json = e.getRequestURI().getQuery().contains("SYMBOL_SEARCH")
                    ? "{\"bestMatches\":[{\"1. symbol\":\"IBM\",\"2. name\":\"IBM\",\"3. type\":\"Equity\",\"8. currency\":\"" + currency + "\"}]}"
                    : history;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status,bytes.length);
            try (var out = e.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        api = new MarketDataApi("test", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/query"));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void alpacaCatalogLoadsOnceAndFiltersUnavailableAssets() throws Exception {
        server.createContext("/v2/assets", e -> {
            requests.incrementAndGet();
            String item = """
                    {"symbol":"NVDA","name":"NVIDIA Corporation","exchange":"NASDAQ","status":"active","class":"us_equity","tradable":true}
                    """.trim();
            String body = "[" + String.join(",",item,item.replace("NVDA","HALT").replace("true","false"),
                    item.replace("NVDA","OLD").replace("active","inactive"),item.replace("NVDA","COIN").replace("us_equity","crypto"),
                    item.replace("NVDA","PINK").replace("NASDAQ","OTC")) + "]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            boolean valid = e.getRequestURI().getQuery().contains("asset_class=us_equity")
                    && "secret".equals(e.getRequestHeaders().getFirst("APCA-API-SECRET-KEY"));
            e.sendResponseHeaders(valid ? 200 : 403,bytes.length);
            try (var out = e.getResponseBody()) { out.write(bytes); }
        });
        var api = new MarketDataApi("id",URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stocks/"),"secret");
        assertEquals(java.util.List.of(new MarketDataApi.Instrument("NVDA","NVIDIA Corporation","NASDAQ")),api.catalog());
        api.catalog();
        assertEquals(1,requests.get());
    }
    @Test void alphaCatalogHandlesQuotaThenQuotedCsvNames() throws Exception {
        history = "{\"Information\":\"quota\"}";
        assertThrows(IOException.class,api::catalog);
        history = """
                symbol,name,exchange,assetType,ipoDate,delistingDate,status
                AMD,"Advanced Micro Devices, Inc.",NASDAQ,Stock,1972-01-01,,Active
                SPY,SPDR S&P 500,NYSE ARCA,ETF,1993-01-01,,Active
                OLD,Old company,NYSE,Stock,1970-01-01,2025-01-01,Delisted
                """;
        var items = api.catalog();
        assertEquals(2,items.size());
        assertEquals("Advanced Micro Devices, Inc.",items.getFirst().name());
    }
    @Test void latestDatedCloseAndCache() throws Exception {
        var s = api.load("ibm",false);
        equalAmount("110", s.price());
        assertEquals("2025-01-07",s.date().toString());
        assertSame(s,api.load("IBM",false));
        assertEquals(2,requests.get());
        api.load("IBM",true);
        assertEquals(3,requests.get());
    }
    @Test void quotaMissingDataAndNetworkErrors() {
        for (String response : new String[]{"{\"Note\":\"quota\"}", "{\"Information\":\"limit\"}",
                "{}", "not json", history.replace("110","0"),history.replace("2025-01-07","bad-date")}) {
            history = response;
            assertThrows(IOException.class,() -> api.load("IBM",false));
        }
        status = 503;
        assertThrows(IOException.class,() -> api.load("IBM",true));
    }
    @Test void rejectsNonUsdAndUnknownSymbols() {
        currency = "EUR";
        assertThrows(IOException.class,() -> api.load("IBM",false));
        assertThrows(IOException.class,() -> api.load("XYZ",false));
        assertEquals(2,requests.get());
    }

    @Test void iexRefreshesTradesIndependentlyOfDailyBars(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var barCalls = new AtomicInteger();
        var tradeCalls = new AtomicInteger();
        server.createContext("/stocks/IBM/", e -> {
            boolean validHeaders = "id".equals(e.getRequestHeaders().getFirst("APCA-API-KEY-ID")) &&
                    "secret".equals(e.getRequestHeaders().getFirst("APCA-API-SECRET-KEY"));
            int code = validHeaders && e.getRequestURI().getQuery().contains("feed=iex") ? 200 : 403;
            String body;
            if (e.getRequestURI().getPath().endsWith("/bars")) {
                barCalls.incrementAndGet();
                body = """
                    {"symbol":"IBM","bars":[{"t":"2025-01-06T05:00:00Z","c":100},
                    {"t":"2025-01-07T05:00:00Z","c":110},{"t":"2025-01-08T05:00:00Z","c":108}]}
                    """;
            } else {
                int count = tradeCalls.incrementAndGet();
                body = "{\"symbol\":\"IBM\",\"trade\":{\"t\":\"2025-01-09T15:30:01.123456789Z\",\"p\":" + (120+count) + "}}";
                if (count == 3) code = 429;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(code,bytes.length);
            try (var out = e.getResponseBody()) { out.write(bytes); }
        });
        api = new MarketDataApi("id",URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stocks/"),"secret");
        var file = dir.resolve("portfolio.json");
        var initial = Portfolio.create("IEX",bd("1000"));
        try (var service = new PortfolioService(file,api,initial)) {
            var first = service.quote("IBM");
            equalAmount("121",first.price());
            equalAmount("108",first.prices().lastEntry().getValue());
            service.trade("IBM",bd("2"),true);
            equalAmount("756",service.portfolio().cash());
            assertThrows(IOException.class,() -> service.trade("IBM",bd("1"),true));
            assertEquals(1,service.portfolio().trades().size());
            assertEquals(1,barCalls.get());
        }
        try (var restored = new PortfolioService(file,api,initial)) {
            var trade = restored.portfolio().trades().getFirst();
            assertEquals("2025-01-09T15:30:01.123456789Z",trade.priceDate());
            equalAmount("122",trade.price());
        }
    }
}
