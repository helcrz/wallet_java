package fr.insa.portefeuille;

import java.math.BigDecimal;
import java.math.MathContext;

public record AssetPosition(String symbol, String name, String type, String currency,
                            BigDecimal quantity, BigDecimal averageBuyPrice) {
    static final MathContext PRECISION = MathContext.DECIMAL128;

    public AssetPosition {
        if (symbol == null || symbol.isBlank() || name == null || name.isBlank() ||
                type == null || type.isBlank() || !"USD".equals(currency))
            throw new IllegalArgumentException("Description de position invalide.");
        positive(quantity, "Quantité");
        positive(averageBuyPrice, "Prix moyen");
    }

    static void positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0 || !Double.isFinite(value.doubleValue())
                || value.doubleValue() == 0)
            throw new IllegalArgumentException(label + " : une valeur strictement positive est requise.");
    }

    public BigDecimal cost() { return quantity.multiply(averageBuyPrice); }
    public BigDecimal value(BigDecimal price) { return quantity.multiply(price); }
    public BigDecimal profit(BigDecimal price) { return value(price).subtract(cost()); }

    AssetPosition buy(BigDecimal amount, BigDecimal price) {
        BigDecimal total = quantity.add(amount);
        BigDecimal average = cost().add(amount.multiply(price)).divide(total, PRECISION);
        return new AssetPosition(symbol, name, type, currency, total, average);
    }

    AssetPosition withQuantity(BigDecimal amount) {
        return new AssetPosition(symbol, name, type, currency, amount, averageBuyPrice);
    }
}
