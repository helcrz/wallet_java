package fr.insa.portefeuille;

import java.math.BigDecimal;
import java.util.*;

public record Portfolio(String name, String currency, BigDecimal initialCash, BigDecimal cash,
                        Profile profile, List<AssetPosition> assets, List<Trade> trades) {
    public enum Profile {
        PRUDENT("Prudent", .10, .25), EQUILIBRE("Équilibré", .20, .40), DYNAMIQUE("Dynamique", .30, .60);
        final double volatilityLimit, weightLimit;
        private final String label;
        Profile(String label, double vol, double weight) {
            this.label = label; volatilityLimit = vol; weightLimit = weight;
        }
        @Override public String toString() { return label; }
    }
    public record Trade(String date, String priceDate, String symbol, boolean purchase,
                        BigDecimal quantity, BigDecimal price) {
        public Trade {
            java.time.LocalDate.parse(date);
            if (priceDate.contains("T")) java.time.Instant.parse(priceDate);
            else java.time.LocalDate.parse(priceDate);
            Objects.requireNonNull(symbol);
            AssetPosition.positive(quantity, "Quantité");
            AssetPosition.positive(price, "Cours");
        }
    }

    public Portfolio {
        if (name == null || name.isBlank() || !"USD".equals(currency) || cash == null || cash.signum() < 0)
            throw new IllegalArgumentException("Portefeuille invalide.");
        AssetPosition.positive(initialCash, "Capital initial");
        Objects.requireNonNull(profile);
        assets = List.copyOf(assets);
        trades = List.copyOf(trades);
        if (assets.stream().map(AssetPosition::symbol).distinct().count() != assets.size())
            throw new IllegalArgumentException("Position en double.");
    }

    public static Portfolio create(String name, BigDecimal capital) {
        return new Portfolio(name, "USD", capital, capital, Profile.EQUILIBRE, List.of(), List.of());
    }
    public Optional<AssetPosition> find(String symbol) {
        return assets.stream().filter(a -> a.symbol().equals(symbol)).findFirst();
    }
    public Portfolio withProfile(Profile next) {
        return new Portfolio(name, currency, initialCash, cash, next, assets, trades);
    }

    Portfolio transact(MarketDataApi.Series series, BigDecimal amount, boolean purchase) {
        AssetPosition.positive(amount, "Quantité");
        BigDecimal price = series.price();
        BigDecimal cost = amount.multiply(price);
        BigDecimal balance = purchase ? cash.subtract(cost) : cash.add(cost);
        if (balance.signum() < 0) throw new IllegalArgumentException("Liquidités insuffisantes.");
        var positions = new ArrayList<>(assets);
        var existing = find(series.symbol());
        if (purchase && existing.isEmpty()) {
            positions.add(new AssetPosition(series.symbol(), series.name(), series.type(), currency, amount, price));
        } else {
            var position = existing.orElseThrow(() -> new IllegalArgumentException("Aucune position à vendre."));
            int index = positions.indexOf(position);
            if (purchase) positions.set(index, position.buy(amount, price));
            else {
                BigDecimal remaining = position.quantity().subtract(amount);
                if (remaining.signum() < 0) throw new IllegalArgumentException("Quantité détenue insuffisante.");
                if (remaining.signum() == 0) positions.remove(index);
                else positions.set(index, position.withQuantity(remaining));
            }
        }
        var journal = new ArrayList<>(trades);
        journal.add(new Trade(java.time.LocalDate.now().toString(), series.priceStamp(),
                series.symbol(), purchase, amount, price));
        return new Portfolio(name, currency, initialCash, balance, profile, positions, journal);
    }
}
