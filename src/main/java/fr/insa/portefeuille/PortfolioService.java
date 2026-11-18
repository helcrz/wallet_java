package fr.insa.portefeuille;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.*;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;

public final class PortfolioService implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    private final MarketDataApi api;
    private final AnalysisService analysis = new AnalysisService();
    private final Path file;
    private final FileChannel lockChannel;
    private Portfolio portfolio;

    public record View(Portfolio portfolio, Map<String,MarketDataApi.Series> market, AnalysisService.Valuation valuation) {}
    public record Report(AnalysisService.Sample sample, AnalysisService.Risk risk, List<String> advice,
                         NavigableMap<LocalDate,BigDecimal> historicalValues) {}
    public record Scenario(AnalysisService.Valuation before, AnalysisService.Valuation after,
                           AnalysisService.Risk riskBefore, Report report, BigDecimal cashAfter, double assetVolatility, double lastReturn) {}
    public record Proposal(AnalysisService.Sample sample, AnalysisService.Allocation allocation, Map<String,Double> actual) {}

    public PortfolioService(Path file, MarketDataApi api, Portfolio initial) throws IOException {
        this.api = api;
        this.file = file.toAbsolutePath();
        Files.createDirectories(this.file.getParent());
        lockChannel = FileChannel.open(this.file.resolveSibling(file.getFileName() + ".lock"), CREATE, WRITE);
        try {
            if (lockChannel.tryLock() == null) throw new IOException("Portefeuille déjà ouvert dans une autre fenêtre.");
            if (Files.notExists(this.file)) commit(initial);
            else portfolio = JSON.readValue(this.file.toFile(), Portfolio.class);
            if (portfolio == null) throw new IOException("Sauvegarde vide.");
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("Ce portefeuille est déjà ouvert.");
        } catch (IOException | RuntimeException e) {
            lockChannel.close();
            throw new IOException("Ouverture de " + this.file + " impossible. " + e.getMessage(), e);
        }
    }

    private void commit(Portfolio next) throws IOException {
        var temp = Files.createTempFile(file.getParent(), "portefeuille-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), next);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            portfolio = next;
        } finally { Files.deleteIfExists(temp); }
    }
    public Portfolio portfolio() { return portfolio; }
    public boolean demo() { return api.demo(); }
    public boolean live() { return api.live(); }
    public List<MarketDataApi.Instrument> catalog() throws IOException { return api.catalog(); }
    public void profile(Portfolio.Profile profile) throws IOException { commit(portfolio.withProfile(profile)); }
    public MarketDataApi.Series quote(String symbol) throws IOException { return api.load(symbol, false); }
    public MarketDataApi.Series refreshQuote(String symbol) throws IOException { return api.load(symbol, true); }
    public MarketDataApi.Series trade(String symbol, BigDecimal quantity, boolean buy) throws IOException {
        var execution = quote(symbol);
        commit(portfolio.transact(execution, quantity, buy));
        return execution;
    }

    public View view(boolean refresh) throws IOException {
        var market = new LinkedHashMap<String,MarketDataApi.Series>();
        for (var asset : portfolio.assets()) market.put(asset.symbol(), api.load(asset.symbol(), refresh));
        return new View(portfolio, market, analysis.value(portfolio, market));
    }
    private Report report(AnalysisService.Sample sample, View view) {
        var risk = analysis.risk(sample, view.valuation().weights());
        return new Report(sample, risk, analysis.recommendations(portfolio.profile(), view.valuation(), risk, sample),
                analysis.historicalValues(view.portfolio(), view.market()));
    }
    public Report report() throws IOException {
        var view = view(false);
        return report(analysis.sample(List.copyOf(view.market().values())), view);
    }

    public Scenario preview(String symbol, BigDecimal amount) throws IOException {
        var proposed = quote(symbol);
        var next = portfolio.transact(proposed, amount, true);
        var market = new LinkedHashMap<String,MarketDataApi.Series>();
        market.put(proposed.symbol(), proposed);
        for (var asset : portfolio.assets())
            if (!market.containsKey(asset.symbol())) market.put(asset.symbol(), quote(asset.symbol()));
        var before = analysis.value(portfolio, market);
        var sample = analysis.sample(List.copyOf(market.values()));
        var after = analysis.value(next, market);
        var prior = proposed.prices().lowerEntry(proposed.date());
        double lastReturn = prior == null ? Double.NaN :
                proposed.price().subtract(prior.getValue()).divide(prior.getValue(), AssetPosition.PRECISION).doubleValue();
        int index = sample.symbols().indexOf(proposed.symbol());
        double volatility = Math.sqrt(AnalysisService.SESSIONS * sample.covariance()[index][index]);
        return new Scenario(before, after, analysis.risk(sample, before.weights()),
                report(sample, new View(next, market, after)), next.cash(), volatility, lastReturn);
    }
    public Proposal optimize(List<String> symbols, double cap, Double target) throws IOException {
        var series = new ArrayList<MarketDataApi.Series>();
        for (String symbol : symbols) series.add(quote(symbol));
        var sample = analysis.sample(series);
        return new Proposal(sample, analysis.optimize(sample, cap, target), view(false).valuation().weights());
    }
    @Override public void close() throws IOException { lockChannel.close(); }
}
