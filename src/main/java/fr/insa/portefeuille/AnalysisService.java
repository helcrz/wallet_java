package fr.insa.portefeuille;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.ojalgo.optimisation.ExpressionsBasedModel;

public final class AnalysisService {
    static final int SESSIONS = 252;
    public record Valuation(BigDecimal total, BigDecimal profit, double performance,
                            Map<String,Double> weights, Map<String,Double> byType) {}
    public record Sample(List<String> symbols, LocalDate start, LocalDate end, int observations,
                         double[] mean, double[][] covariance) {
        double correlation(int i, int j) {
            double divisor = Math.sqrt(covariance[i][i] * covariance[j][j]);
            return divisor <= 1e-16 ? Double.NaN : Math.clamp(covariance[i][j] / divisor, -1, 1);
        }
    }
    public record Risk(double annualReturn, double volatility, double sharpe) {}
    public record Allocation(Map<String,Double> weights, Risk risk) {}

    public Valuation value(Portfolio p, Map<String,MarketDataApi.Series> market) {
        BigDecimal total = p.cash();
        var amounts = new LinkedHashMap<String,BigDecimal>();
        var groups = new TreeMap<String,BigDecimal>();
        groups.put("Liquidités", p.cash());
        for (var a : p.assets()) {
            var series = market.get(a.symbol());
            if (series == null) throw new IllegalArgumentException("Cours manquant pour " + a.symbol());
            var amount = a.value(series.price());
            total = total.add(amount);
            amounts.put(a.symbol(), amount);
            groups.merge(a.type(), amount, BigDecimal::add);
        }
        BigDecimal profit = total.subtract(p.initialCash());
        return new Valuation(total, profit, ratio(profit, p.initialCash()), fractions(amounts, total), fractions(groups, total));
    }

    /** Revalorisation des quantités actuelles aux dates communes, liquidités constantes. */
    public NavigableMap<LocalDate,BigDecimal> historicalValues(Portfolio p, Map<String,MarketDataApi.Series> market) {
        var history = new TreeMap<LocalDate,BigDecimal>();
        if (p.assets().isEmpty()) return history;
        var dates = new TreeSet<>(market.get(p.assets().getFirst().symbol()).prices().keySet());
        for (var asset : p.assets()) dates.retainAll(market.get(asset.symbol()).prices().keySet());
        for (var date : dates) {
            BigDecimal total = p.cash();
            for (var asset : p.assets()) total = total.add(asset.value(market.get(asset.symbol()).prices().get(date)));
            history.put(date,total);
        }
        return history;
    }

    private static double ratio(BigDecimal amount, BigDecimal total) {
        return amount.divide(total, AssetPosition.PRECISION).doubleValue();
    }
    private static Map<String,Double> fractions(Map<String,BigDecimal> amounts, BigDecimal total) {
        var result = new LinkedHashMap<String,Double>();
        amounts.forEach((k,v) -> result.put(k, ratio(v, total)));
        return Collections.unmodifiableMap(result);
    }

    public Sample sample(List<MarketDataApi.Series> series) {
        if (series.isEmpty()) throw new IllegalArgumentException("Sélectionne au moins un actif.");
        var symbols = series.stream().map(MarketDataApi.Series::symbol).toList();
        if (new HashSet<>(symbols).size() != symbols.size()) throw new IllegalArgumentException("Symbole en double.");
        var dates = new TreeSet<>(series.getFirst().prices().keySet());
        series.forEach(s -> dates.retainAll(s.prices().keySet()));
        int n = series.size();
        double[] means = new double[n];
        var rows = new ArrayList<double[]>();
        LocalDate first = null, last = null;
        for (LocalDate end : dates) {
            LocalDate start = dates.lower(end);
            if (start == null || !series.stream().allMatch(s -> start.equals(s.prices().lowerKey(end)))) continue;
            double[] row = new double[n];
            for (int i = 0; i < n; i++) {
                var prices = series.get(i).prices();
                row[i] = ratio(prices.get(end).subtract(prices.get(start)), prices.get(start));
                means[i] += row[i];
            }
            rows.add(row);
            if (first == null) first = start;
            last = end;
        }
        if (rows.size() < 2) throw new IllegalArgumentException("Il faut au moins deux rendements sur des séances communes consécutives.");
        for (int i = 0; i < n; i++) means[i] /= rows.size();
        double[][] cov = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0;
                for (double[] row : rows) sum += (row[i] - means[i]) * (row[j] - means[j]);
                cov[i][j] = cov[j][i] = sum / (rows.size() - 1);
            }
        }
        return new Sample(symbols, first, last, rows.size(), means, cov);
    }

    public Risk risk(Sample sample, Map<String,Double> weights) {
        double mean = 0, variance = 0;
        for (int i = 0; i < sample.symbols().size(); i++) {
            double wi = weights.getOrDefault(sample.symbols().get(i), 0d);
            mean += wi * sample.mean()[i];
            for (int j = 0; j < sample.symbols().size(); j++)
                variance += wi * weights.getOrDefault(sample.symbols().get(j), 0d) * sample.covariance()[i][j];
        }
        double vol = Math.sqrt(Math.max(0, variance) * SESSIONS);
        return new Risk(mean * SESSIONS, vol, vol < 1e-12 ? Double.NaN : mean * SESSIONS / vol);
    }

    public Allocation optimize(Sample sample, double cap, Double target) {
        int n = sample.symbols().size();
        if (!Double.isFinite(cap) || cap <= 0 || cap > 1 || cap * n < 1 - 1e-12)
            throw new IllegalArgumentException("Plafond incompatible avec une allocation totale de 100 %.");
        if (target != null && !Double.isFinite(target)) throw new IllegalArgumentException("Rendement cible invalide.");
        var model = new ExpressionsBasedModel();
        try {
            var budget = model.newExpression("budget").level(1);
            var variance = model.newExpression("variance").weight(1);
            var floor = target == null ? null : model.newExpression("rendement").lower(target);
            for (int i = 0; i < n; i++) model.newVariable(sample.symbols().get(i)).lower(0).upper(cap);
            for (int i = 0; i < n; i++) {
                budget.set(i, 1);
                if (floor != null) floor.set(i, sample.mean()[i] * SESSIONS);
                for (int j = 0; j <= i; j++)
                    variance.set(i, j, (i == j ? 1 : 2) * SESSIONS * sample.covariance()[i][j]);
            }
            var result = model.minimise();
            if (!result.getState().isOptimal()) throw new IllegalArgumentException("Aucun optimum trouvé. Vérifie la cible et le plafond.");
            var weights = new LinkedHashMap<String,Double>();
            for (int i = 0; i < n; i++) weights.put(sample.symbols().get(i), Math.max(0, result.doubleValue(i)));
            return new Allocation(Collections.unmodifiableMap(weights), risk(sample, weights));
        } finally { model.dispose(); }
    }

    public List<String> recommendations(Portfolio.Profile profile, Valuation valuation, Risk risk, Sample sample) {
        var messages = new ArrayList<String>();
        String level = risk.volatility() > profile.volatilityLimit ? "élevé"
                : risk.volatility() > profile.volatilityLimit / 2 ? "modéré" : "faible";
        messages.add(String.format(Locale.FRANCE, "Risque %s selon la volatilité : %.1f %% par an (seuil %s : %.0f %%).",
                level, risk.volatility()*100, profile, profile.volatilityLimit*100));
        valuation.weights().forEach((k,w) -> {
            if (w > profile.weightLimit) messages.add(String.format(Locale.FRANCE,
                    "Concentration : %s pèse %.1f %% ; repère du profil %.0f %%.", k, w*100, profile.weightLimit*100));
        });
        if (valuation.weights().size() == 1) messages.add("Une seule position : la diversification entre titres est limitée.");
        for (int i = 0; i < sample.symbols().size(); i++)
            for (int j = 0; j < i; j++)
                if (sample.correlation(i,j) > .80) messages.add(String.format(Locale.FRANCE,
                        "%s et %s évoluent souvent ensemble (corrélation %.2f, seuil 0,80).",
                        sample.symbols().get(i), sample.symbols().get(j), sample.correlation(i,j)));
        return List.copyOf(messages);
    }
}
