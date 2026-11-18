package fr.insa.portefeuille;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PortfolioTest {
    @TempDir Path dir;
    private final Portfolio start = Portfolio.create("Atelier", bd("10000"));
    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static MarketDataApi.Series series(String symbol, String... prices) {
        var data = new TreeMap<LocalDate,BigDecimal>();
        for (int i=0; i<prices.length; i++) data.put(LocalDate.of(2025,1,6).plusDays(i), bd(prices[i]));
        return new MarketDataApi.Series(symbol,symbol,"Action",data);
    }
    static void equalAmount(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual)); }

    @Test void weightedCostAndRealizedGain() {
        var low = series("IBM","100");
        var high = series("IBM","150");
        var p = start.transact(low,bd("2"),true).transact(high,bd("3"),true);
        equalAmount("130",p.assets().getFirst().averageBuyPrice());
        p = p.transact(high,bd("2"),false);
        var view = new AnalysisService().value(p,Map.of("IBM",high));
        equalAmount("60",p.assets().getFirst().profit(high.price()));
        equalAmount("10100",view.total());
        assertEquals(.01,view.performance(),1e-10);
        var sold = p.transact(high,bd("3"),false);
        assertTrue(sold.assets().isEmpty());
        equalAmount("10100",sold.cash());
    }
    @Test void invalidTradesAreRejected() {
        var s = series("IBM","100");
        assertThrows(IllegalArgumentException.class,() -> start.transact(s,bd("101"),true));
        assertThrows(IllegalArgumentException.class,() -> start.transact(s,bd("-1"),true));
        assertThrows(IllegalArgumentException.class,() -> start.transact(s,bd("0"),false));
        assertThrows(IllegalArgumentException.class,() -> start.transact(s,bd("1"),false));
        var p = start.transact(s,bd("0.25"),true);
        assertThrows(IllegalArgumentException.class,() -> p.transact(s,bd(".251"),false));
        equalAmount("9975",p.cash());
    }
    @Test void persistenceAndReadOnlyScenarios() throws Exception {
        Path file = dir.resolve("book.json");
        Portfolio expected;
        try (var service = open(file)) {
            service.trade("ibm",bd("1.5"),true);
            service.profile(Portfolio.Profile.PRUDENT);
            String before = Files.readString(file);
            var preview = service.preview("SPY",bd("1"));
            assertTrue(preview.cashAfter().compareTo(service.portfolio().cash()) < 0);
            assertEquals(preview.before().total(),preview.after().total());
            var proposal = service.optimize(List.of("IBM","SPY"),.75,null);
            assertEquals(1d,proposal.allocation().weights().values().stream().mapToDouble(v->v).sum(),1e-8);
            assertThrows(IllegalArgumentException.class,() -> service.trade("IBM",bd("2"),false));
            assertEquals(before,Files.readString(file));
            assertThrows(java.io.IOException.class,() -> open(file));
            expected = service.portfolio();
        }
        try (var loaded = open(file)) { assertEquals(expected,loaded.portfolio()); }
    }
    @Test void damagedSavePreserved() throws Exception {
        var file = dir.resolve("book.json");
        Files.writeString(file,"{");
        assertThrows(java.io.IOException.class,() -> open(file));
        assertEquals("{",Files.readString(file));
    }
    private PortfolioService open(Path path) throws Exception {
        return new PortfolioService(path,new MarketDataApi(""),start);
    }
}
