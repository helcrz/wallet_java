package fr.insa.portefeuille;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static fr.insa.portefeuille.PortfolioTest.*;

class AnalysisTest {
    final AnalysisService analysis = new AnalysisService();
    @Test void knownReturnsCovarianceAndCashWeight() {
        var a = series("A","100","110","99");
        var b = series("B","100","90","99");
        var s = analysis.sample(List.of(a,b));
        assertEquals(0,s.mean()[0],1e-14);
        assertEquals(.02,s.covariance()[0][0],1e-14);
        assertEquals(-1,s.correlation(0,1),1e-14);
        var halfCash = analysis.risk(s,Map.of("A",.5));
        assertEquals(.5*Math.sqrt(.02*252),halfCash.volatility(),1e-10);
        assertEquals(0,halfCash.sharpe(),1e-12);
        assertEquals(0,analysis.risk(s,Map.of("A",.5,"B",.5)).volatility(),1e-10);
    }
    @Test void constantSeriesHasUndefinedCorrelationAndSharpe() {
        var s = analysis.sample(List.of(series("A","10","10","10")));
        assertTrue(Double.isNaN(s.correlation(0,0)));
        assertTrue(Double.isNaN(analysis.risk(s,Map.of("A",1d)).sharpe()));
    }
    @Test void gapsDoNotMixUnequalReturnIntervals() {
        var a = series("A","10","20","30","40","44","48.4");
        var sparse = new TreeMap<>(a.prices());
        sparse.remove(a.prices().firstKey().plusDays(1));
        var b = new MarketDataApi.Series("B","B","ETF",sparse);
        var s = analysis.sample(List.of(a,b));
        assertEquals(3,s.observations());
        assertEquals(LocalDate.of(2025,1,8),s.start());
        assertThrows(IllegalArgumentException.class,() -> analysis.sample(List.of(series("X","1","2"))));
    }
    @Test void constrainedMarkowitzMatchesAnalyticalSolution() {
        var s = new AnalysisService.Sample(List.of("A","B"),LocalDate.MIN,LocalDate.MAX,50,
                new double[]{.05/252,.15/252},new double[][]{{.0001,0},{0,.0004}});
        var optimum = analysis.optimize(s,1,null);
        assertEquals(.8,optimum.weights().get("A"),1e-6);
        assertEquals(.2,optimum.weights().get("B"),1e-6);
        assertEquals(.6,analysis.optimize(s,.6,null).weights().get("A"),1e-6);
        assertEquals(.7,analysis.optimize(s,1,.12).weights().get("B"),1e-6);
        assertThrows(IllegalArgumentException.class,() -> analysis.optimize(s,.4,null));
        assertThrows(IllegalArgumentException.class,() -> analysis.optimize(s,1,.16));
    }
}
