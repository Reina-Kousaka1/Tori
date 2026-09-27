package music;

import java.time.Instant;
import java.util.List;

/** Data transfer types for the persistent dynamic shop. */
final class DeseModels {
    private DeseModels() {}

    record Product(String id, String name, String description, String category, long currentPrice,
                   long basePrice, long minimumPrice, long maximumPrice, double volatility,
                   long stock, Instant createdAt, Instant updatedAt, Instant nextPriceAt,
                   boolean available, String rarity, List<String> tags) {
        Product { tags = tags == null ? List.of() : List.copyOf(tags); }
        boolean unlimitedStock() { return stock < 0; }
    }

    record PricePoint(String productId, long price, Instant at, String reason) {}

    record Sale(String id, String productId, String category, int discountPercent,
                Instant startsAt, Instant endsAt, String status) {
        boolean targets(String product, String productCategory) {
            return productId != null && productId.equals(product)
                || category != null && category.equals(productCategory);
        }
        boolean activeAt(Instant at) {
            return "ACTIVE".equals(status) && !at.isBefore(startsAt) && at.isBefore(endsAt);
        }
    }

    record Quote(long price, long expiresAtMillis) {}

    enum PurchaseState { PURCHASED, PRICE_CHANGED, QUOTE_REQUIRED, UNAVAILABLE, OUT_OF_STOCK, INSUFFICIENT_FUNDS, DUPLICATE }

    record Purchase(PurchaseState state, Product product, int quantity, long unitPrice,
                    long total, long balanceBefore, long balanceAfter, String transactionId) {}
}
