package music;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/** PostgreSQL-backed DESE market. Quotes, stock, wallet, inventory and idempotency share one transaction. */
final class PostgresMarketStore {
    static final Duration PRICE_PERIOD = Duration.ofHours(1);
    static final Duration QUOTE_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_PRICE_POINTS_PER_PRODUCT = 17_520;

    private final PostgresDatabase database;
    private final DoubleSupplier random;
    private final MarketMode mode;

    PostgresMarketStore(PostgresDatabase database) { this(database, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble()); }
    PostgresMarketStore(PostgresDatabase database, DoubleSupplier random) {
        this(database, random, MarketMode.LEGACY);
    }
    PostgresMarketStore(PostgresDatabase database, DoubleSupplier random, MarketMode mode) {
        this.database = Objects.requireNonNull(database);
        this.random = Objects.requireNonNull(random);
        this.mode = Objects.requireNonNull(mode);
    }

    boolean readOnly() { return mode != MarketMode.LEGACY; }

    /** Seeds only missing static catalog rows; existing prices, stock and availability are not overwritten. */
    void seedCatalog(Instant now) throws CurrencyStoreException {
        mode.requireWritable();
        transaction(connection -> {
            for (ShopCatalog.Item item : ShopCatalog.market()) {
                long base = item.price();
                long minimum = Math.max(1, Math.round(base * 0.70));
                long maximum = Math.max(base, Math.round(base * 1.30));
                try (var sql = connection.prepareStatement("""
                    INSERT INTO economy_market_products(product_id,name,description,category,current_price,base_price,
                        minimum_price,maximum_price,volatility,stock,available,rarity,tags,created_at,updated_at,next_price_at)
                    VALUES (?,?,?,?,?,?,?,?,0.05,-1,TRUE,'common',ARRAY[]::TEXT[],?,?,?)
                    ON CONFLICT(product_id) DO NOTHING
                    """)) {
                    sql.setString(1, item.id()); sql.setString(2, item.name()); sql.setString(3, item.description());
                    sql.setString(4, item.category()); sql.setLong(5, base); sql.setLong(6, base);
                    sql.setLong(7, minimum); sql.setLong(8, maximum);
                    PostgresTimestamps.bind(sql, 9, now); PostgresTimestamps.bind(sql, 10, now);
                    PostgresTimestamps.bind(sql, 11, now.plus(PRICE_PERIOD));
                    sql.executeUpdate();
                }
            }
            return null;
        });
    }

    DeseModels.Product product(String id, Instant now) throws CurrencyStoreException {
        String productId = normalizeId(id);
        if (!validProductId(productId)) return null;
        if (!readOnly()) evolveProductIfDue(productId, now);
        return transaction(connection -> {
            try (var sql = connection.prepareStatement("SELECT * FROM economy_market_products WHERE product_id=?")) {
                sql.setString(1, productId);
                try (var rows = sql.executeQuery()) { return rows.next() ? readProduct(rows) : null; }
            }
        });
    }

    List<DeseModels.Product> products(String category, Instant now) throws CurrencyStoreException {
        String selected = category == null || category.isBlank() ? "all" : category.strip().toLowerCase(Locale.ROOT);
        if (!selected.equals("all") && !selected.equals("utility") && !selected.matches("[a-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid market category");
        List<String> ids = transaction(connection -> {
            String sqlText = selected.equals("all") ? "SELECT product_id FROM economy_market_products ORDER BY product_id"
                : selected.equals("utility")
                    ? "SELECT product_id FROM economy_market_products WHERE category IN ('common','tools') ORDER BY product_id"
                    : "SELECT product_id FROM economy_market_products WHERE category=? ORDER BY product_id";
            try (var sql = connection.prepareStatement(sqlText)) {
                if (!selected.equals("all") && !selected.equals("utility")) sql.setString(1, selected);
                try (var rows = sql.executeQuery()) {
                    var found = new ArrayList<String>();
                    while (rows.next()) found.add(rows.getString(1));
                    return List.copyOf(found);
                }
            }
        });
        var result = new ArrayList<DeseModels.Product>(ids.size());
        for (String id : ids) {
            DeseModels.Product product = product(id, now);
            if (product != null) result.add(product);
        }
        return List.copyOf(result);
    }

    /** Creates a server/user/product-bound quote; client-provided prices are never accepted. */
    long quote(String guildId, String userId, String productId, Instant now) throws CurrencyStoreException {
        if (!validSnowflake(guildId) || !validSnowflake(userId)) return -1;
        DeseModels.Product product = product(productId, now);
        if (product == null || !product.available() || !product.unlimitedStock() && product.stock() <= 0) return -1;
        if (readOnly()) return transaction(connection -> effectivePrice(connection, product, now, false));
        return transaction(connection -> {
            long quoted = effectivePrice(connection, product, now, true);
            try (var sql = connection.prepareStatement("""
                INSERT INTO economy_market_quotes(guild_id,user_id,product_id,quoted_price,quoted_at,expires_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(guild_id,user_id,product_id) DO UPDATE SET
                    quoted_price=EXCLUDED.quoted_price,quoted_at=EXCLUDED.quoted_at,expires_at=EXCLUDED.expires_at
                """)) {
                sql.setString(1, guildId); sql.setString(2, userId); sql.setString(3, product.id());
                sql.setLong(4, quoted); PostgresTimestamps.bind(sql, 5, now);
                PostgresTimestamps.bind(sql, 6, now.plus(QUOTE_LIFETIME));
                sql.executeUpdate();
            }
            return quoted;
        });
    }

    List<DeseModels.PricePoint> history(String id, int limit) throws CurrencyStoreException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("History limit must be 1–100");
        String productId = normalizeId(id);
        return transaction(connection -> {
            try (var sql = connection.prepareStatement("""
                SELECT price,changed_at,reason FROM economy_market_price_history
                WHERE product_id=? ORDER BY history_id DESC LIMIT ?
                """)) {
                sql.setString(1, productId); sql.setInt(2, limit);
                try (var rows = sql.executeQuery()) {
                    var found = new ArrayList<DeseModels.PricePoint>();
                    while (rows.next()) found.add(new DeseModels.PricePoint(productId, rows.getLong(1),
                        instant(rows.getTimestamp(2)), rows.getString(3)));
                    return List.copyOf(found);
                }
            }
        });
    }

    DeseModels.Purchase buy(String guildId, String userId, String interactionId, String itemId,
                           int quantity, Instant now) throws CurrencyStoreException {
        mode.requireWritable();
        String productId = normalizeId(itemId);
        if (!validSnowflake(guildId) || !validSnowflake(userId) || !validInteractionId(interactionId)
            || !validProductId(productId) || quantity < 1 || quantity > 100)
            return empty(DeseModels.PurchaseState.UNAVAILABLE, quantity);
        try {
            return transaction(connection -> buyInTransaction(connection, guildId, userId, interactionId, productId, quantity, now));
        } catch (PurchaseAbort abort) {
            return abort.purchase;
        } catch (CurrencyStoreException failure) {
            DeseModels.Purchase prior = priorPurchase(interactionId);
            if (prior != null) return withState(prior, DeseModels.PurchaseState.DUPLICATE);
            throw failure;
        }
    }

    private DeseModels.Purchase buyInTransaction(Connection connection, String guildId, String userId,
                                                  String interactionId, String productId, int quantity,
                                                  Instant now) throws Exception {
        DeseModels.Purchase prior = priorPurchase(connection, interactionId);
        if (prior != null) return withState(prior, DeseModels.PurchaseState.DUPLICATE);

        DeseModels.Product product;
        try (var sql = connection.prepareStatement("SELECT * FROM economy_market_products WHERE product_id=? FOR UPDATE")) {
            sql.setString(1, productId);
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) return empty(DeseModels.PurchaseState.UNAVAILABLE, quantity);
                product = readProduct(rows);
            }
        }
        if (!product.available()) return empty(DeseModels.PurchaseState.UNAVAILABLE, quantity);
        if (!product.unlimitedStock() && product.stock() < quantity)
            return new DeseModels.Purchase(DeseModels.PurchaseState.OUT_OF_STOCK, product, quantity,
                product.currentPrice(), 0, 0, 0, "");

        DeseModels.Sale sale = activeSale(connection, product, now);
        long unitPrice = effectivePrice(product, sale);
        try (var sql = connection.prepareStatement("""
            SELECT quoted_price FROM economy_market_quotes
            WHERE guild_id=? AND user_id=? AND product_id=? AND expires_at>?
            FOR UPDATE
            """)) {
            sql.setString(1, guildId); sql.setString(2, userId); sql.setString(3, productId);
            PostgresTimestamps.bind(sql, 4, now);
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) return new DeseModels.Purchase(DeseModels.PurchaseState.QUOTE_REQUIRED,
                    product, quantity, unitPrice, 0, 0, 0, "");
                if (rows.getLong(1) != unitPrice) return new DeseModels.Purchase(DeseModels.PurchaseState.PRICE_CHANGED,
                    product, quantity, unitPrice, 0, 0, 0, "");
            }
        }
        long total;
        try { total = Math.multiplyExact(unitPrice, quantity); }
        catch (ArithmeticException ex) { return empty(DeseModels.PurchaseState.UNAVAILABLE, quantity); }

        try (var sql = connection.prepareStatement("INSERT INTO economy_accounts(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING")) {
            sql.setString(1, userId); sql.executeUpdate();
        }
        long balanceBefore;
        try (var sql = connection.prepareStatement("SELECT balance FROM economy_accounts WHERE user_id=? FOR UPDATE")) {
            sql.setString(1, userId);
            try (var rows = sql.executeQuery()) { if (!rows.next()) throw new SQLException("Account disappeared"); balanceBefore = rows.getLong(1); }
        }
        if (balanceBefore < total) throw new PurchaseAbort(new DeseModels.Purchase(
            DeseModels.PurchaseState.INSUFFICIENT_FUNDS, product, quantity, unitPrice, total, balanceBefore, balanceBefore, ""));

        if (!product.unlimitedStock()) {
            try (var sql = connection.prepareStatement("UPDATE economy_market_products SET stock=stock-? WHERE product_id=? AND stock>=? AND available")) {
                sql.setInt(1, quantity); sql.setString(2, productId); sql.setInt(3, quantity);
                if (sql.executeUpdate() != 1) throw new PurchaseAbort(new DeseModels.Purchase(
                    DeseModels.PurchaseState.OUT_OF_STOCK, product, quantity, unitPrice, total, balanceBefore, balanceBefore, ""));
            }
        }
        long balanceAfter = balanceBefore - total;
        try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=?,updated_at=? WHERE user_id=? AND balance>=?")) {
            sql.setLong(1, balanceAfter); PostgresTimestamps.bind(sql, 2, now);
            sql.setString(3, userId); sql.setLong(4, total);
            if (sql.executeUpdate() != 1) throw new PurchaseAbort(new DeseModels.Purchase(
                DeseModels.PurchaseState.INSUFFICIENT_FUNDS, product, quantity, unitPrice, total, balanceBefore, balanceBefore, ""));
        }
        try (var sql = connection.prepareStatement("""
            INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES (?,?,?)
            ON CONFLICT(user_id,item_id) DO UPDATE SET quantity=economy_inventory.quantity+EXCLUDED.quantity
            """)) {
            sql.setString(1, userId); sql.setString(2, productId); sql.setInt(3, quantity); sql.executeUpdate();
        }
        String transactionId = UUID.randomUUID().toString();
        try (var sql = connection.prepareStatement("""
            INSERT INTO economy_market_transactions(transaction_id,interaction_id,guild_id,user_id,product_id,
                quantity,unit_price,amount,balance_before,balance_after,occurred_at,sale_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            sql.setString(1, transactionId); sql.setString(2, interactionId); sql.setString(3, guildId);
            sql.setString(4, userId); sql.setString(5, productId); sql.setInt(6, quantity); sql.setLong(7, unitPrice);
            sql.setLong(8, -total); sql.setLong(9, balanceBefore); sql.setLong(10, balanceAfter);
            PostgresTimestamps.bind(sql, 11, now); sql.setString(12, sale == null ? null : sale.id()); sql.executeUpdate();
        }
        try (var sql = connection.prepareStatement("""
            INSERT INTO economy_market_inventory(guild_id,user_id,product_id,quantity,acquired_at,transaction_id)
            VALUES (?,?,?,?,?,?)
            """)) {
            sql.setString(1, guildId); sql.setString(2, userId); sql.setString(3, productId);
            sql.setInt(4, quantity); PostgresTimestamps.bind(sql, 5, now);
            sql.setString(6, transactionId); sql.executeUpdate();
        }
        try (var sql = connection.prepareStatement("DELETE FROM economy_market_quotes WHERE guild_id=? AND user_id=? AND product_id=?")) {
            sql.setString(1, guildId); sql.setString(2, userId); sql.setString(3, productId); sql.executeUpdate();
        }
        return new DeseModels.Purchase(DeseModels.PurchaseState.PURCHASED, product, quantity, unitPrice,
            total, balanceBefore, balanceAfter, transactionId);
    }

    boolean createProduct(DeseModels.Product product) throws CurrencyStoreException {
        mode.requireWritable();
        validateProduct(product);
        return transaction(connection -> {
            try (var sql = connection.prepareStatement("""
                INSERT INTO economy_market_products(product_id,name,description,category,current_price,base_price,
                    minimum_price,maximum_price,volatility,stock,available,rarity,tags,created_at,updated_at,next_price_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(product_id) DO NOTHING
                """)) {
                sql.setString(1, product.id()); sql.setString(2, product.name()); sql.setString(3, product.description());
                sql.setString(4, product.category()); sql.setLong(5, product.currentPrice()); sql.setLong(6, product.basePrice());
                sql.setLong(7, product.minimumPrice()); sql.setLong(8, product.maximumPrice());
                sql.setDouble(9, product.volatility()); sql.setLong(10, product.stock()); sql.setBoolean(11, product.available());
                sql.setString(12, product.rarity()); sql.setArray(13, connection.createArrayOf("text", product.tags().toArray(String[]::new)));
                PostgresTimestamps.bind(sql, 14, product.createdAt());
                PostgresTimestamps.bind(sql, 15, product.updatedAt());
                PostgresTimestamps.bind(sql, 16, product.nextPriceAt());
                return sql.executeUpdate() == 1;
            }
        });
    }

    boolean setStock(String id, long stock, Instant now) throws CurrencyStoreException {
        mode.requireWritable();
        if (stock < -1) throw new IllegalArgumentException("Stock must be -1 (unlimited) or non-negative");
        String productId = normalizeId(id);
        if (!validProductId(productId)) return false;
        return transaction(connection -> {
            try (var sql = connection.prepareStatement("UPDATE economy_market_products SET stock=?,updated_at=? WHERE product_id=?")) {
                sql.setLong(1, stock); PostgresTimestamps.bind(sql, 2, now);
                sql.setString(3, productId); return sql.executeUpdate() == 1;
            }
        });
    }

    boolean maybeCreateRandomSale(Instant now, List<String> categories, List<String> productIds) throws CurrencyStoreException {
        mode.requireWritable();
        double chance = sample();
        if (chance >= 0.08 || categories.isEmpty() && productIds.isEmpty()) return false;
        return transaction(connection -> {
            expireSales(connection, now);
            try (var sql = connection.prepareStatement("SELECT 1 FROM economy_market_sales WHERE status='ACTIVE' AND ends_at>? LIMIT 1")) {
                PostgresTimestamps.bind(sql, 1, now);
                try (var rows = sql.executeQuery()) { if (rows.next()) return false; }
            }
            boolean byProduct = !productIds.isEmpty() && (categories.isEmpty() || sample() < 0.5);
            String target = byProduct ? productIds.get(randomIndex(productIds.size())) : categories.get(randomIndex(categories.size()));
            int discount = 5 + (int)(sample() * 26);
            Instant ends = now.plus(Duration.ofHours(1 + (int)(sample() * 3)));
            try (var sql = connection.prepareStatement("""
                INSERT INTO economy_market_sales(sale_id,product_id,category,discount_percent,starts_at,ends_at,status,active_slot,created_at)
                VALUES (?,?,?,?,?,?,'ACTIVE','global',?)
                ON CONFLICT (active_slot) WHERE active_slot IS NOT NULL DO NOTHING
                """)) {
                sql.setString(1, UUID.randomUUID().toString());
                sql.setString(2, byProduct ? target : null); sql.setString(3, byProduct ? null : target);
                sql.setInt(4, discount); PostgresTimestamps.bind(sql, 5, now);
                PostgresTimestamps.bind(sql, 6, ends); PostgresTimestamps.bind(sql, 7, now);
                return sql.executeUpdate() == 1;
            }
        });
    }

    void evolveDueProducts(Instant now) throws CurrencyStoreException {
        mode.requireWritable();
        List<String> ids = transaction(connection -> {
            try (var sql = connection.prepareStatement("SELECT product_id FROM economy_market_products WHERE next_price_at<=? ORDER BY next_price_at,product_id")) {
                PostgresTimestamps.bind(sql, 1, now);
                try (var rows = sql.executeQuery()) {
                    var result = new ArrayList<String>(); while (rows.next()) result.add(rows.getString(1)); return List.copyOf(result);
                }
            }
        });
        for (String id : ids) evolveProductIfDue(id, now);
        transaction(connection -> { expireSales(connection, now); return null; });
    }

    List<DeseModels.Sale> activeSales(Instant now) throws CurrencyStoreException {
        return transaction(connection -> {
            if (!readOnly()) expireSales(connection, now);
            try (var sql = connection.prepareStatement("""
                SELECT sale_id,product_id,category,discount_percent,starts_at,ends_at,status
                FROM economy_market_sales WHERE status='ACTIVE' AND starts_at<=? AND ends_at>? ORDER BY starts_at DESC
                """)) {
                PostgresTimestamps.bind(sql, 1, now); PostgresTimestamps.bind(sql, 2, now);
                try (var rows = sql.executeQuery()) {
                    var result = new ArrayList<DeseModels.Sale>();
                    while (rows.next()) result.add(readSale(rows));
                    return List.copyOf(result);
                }
            }
        });
    }

    private void evolveProductIfDue(String id, Instant now) throws CurrencyStoreException {
        transaction(connection -> {
            try (var sql = connection.prepareStatement("SELECT * FROM economy_market_products WHERE product_id=? FOR UPDATE")) {
                sql.setString(1, id);
                try (var rows = sql.executeQuery()) {
                    if (!rows.next()) return null;
                    DeseModels.Product current = readProduct(rows);
                    if (now.isBefore(current.nextPriceAt())) return null;
                    var rules = new DynamicPricing.Rules(current.basePrice(), current.minimumPrice(),
                        current.maximumPrice(), current.volatility());
                    long next = DynamicPricing.next(current.currentPrice(), rules, this::sample);
                    try (var update = connection.prepareStatement("""
                        UPDATE economy_market_products SET current_price=?,updated_at=?,next_price_at=? WHERE product_id=?
                        """)) {
                        update.setLong(1, next); PostgresTimestamps.bind(update, 2, now);
                        PostgresTimestamps.bind(update, 3, now.plus(PRICE_PERIOD));
                        update.setString(4, id); update.executeUpdate();
                    }
                    if (next != current.currentPrice()) {
                        try (var insert = connection.prepareStatement("INSERT INTO economy_market_price_history(product_id,price,changed_at,reason) VALUES (?,?,?,'MARKET_MOVEMENT')")) {
                            insert.setString(1, id); insert.setLong(2, next);
                            PostgresTimestamps.bind(insert, 3, now); insert.executeUpdate();
                        }
                        try (var trim = connection.prepareStatement("""
                            DELETE FROM economy_market_price_history WHERE product_id=? AND history_id NOT IN
                                (SELECT history_id FROM economy_market_price_history WHERE product_id=? ORDER BY history_id DESC LIMIT ?)
                            """)) {
                            trim.setString(1, id); trim.setString(2, id); trim.setInt(3, MAX_PRICE_POINTS_PER_PRODUCT); trim.executeUpdate();
                        }
                    }
                }
            }
            return null;
        });
    }

    private static long effectivePrice(Connection connection, DeseModels.Product product, Instant now, boolean lock) throws SQLException {
        try (var sql = connection.prepareStatement("""
            SELECT discount_percent FROM economy_market_sales WHERE status='ACTIVE' AND starts_at<=? AND ends_at>?
                AND (product_id=? OR category=?) ORDER BY starts_at DESC LIMIT 1
            """ + (lock ? " FOR UPDATE" : ""))) {
            PostgresTimestamps.bind(sql, 1, now); PostgresTimestamps.bind(sql, 2, now);
            sql.setString(3, product.id()); sql.setString(4, product.category());
            try (var rows = sql.executeQuery()) {
                return rows.next() ? discounted(product.currentPrice(), rows.getInt(1)) : product.currentPrice();
            }
        }
    }

    private static DeseModels.Sale activeSale(Connection connection, DeseModels.Product product, Instant now) throws SQLException {
        try (var sql = connection.prepareStatement("""
            SELECT sale_id,product_id,category,discount_percent,starts_at,ends_at,status
            FROM economy_market_sales WHERE status='ACTIVE' AND starts_at<=? AND ends_at>?
                AND (product_id=? OR category=?) ORDER BY starts_at DESC LIMIT 1 FOR UPDATE
            """)) {
            PostgresTimestamps.bind(sql, 1, now); PostgresTimestamps.bind(sql, 2, now);
            sql.setString(3, product.id()); sql.setString(4, product.category());
            try (var rows = sql.executeQuery()) { return rows.next() ? readSale(rows) : null; }
        }
    }

    private static long effectivePrice(DeseModels.Product product, DeseModels.Sale sale) {
        return sale == null ? product.currentPrice() : discounted(product.currentPrice(), sale.discountPercent());
    }

    private static long discounted(long price, int discountPercent) {
        return Math.max(1, Math.round(price * (100.0 - discountPercent) / 100.0));
    }

    private static void expireSales(Connection connection, Instant now) throws SQLException {
        try (var sql = connection.prepareStatement("UPDATE economy_market_sales SET status='EXPIRED',active_slot=NULL WHERE status='ACTIVE' AND ends_at<=?")) {
            PostgresTimestamps.bind(sql, 1, now); sql.executeUpdate();
        }
    }

    private <T> T transaction(SqlWork<T> work) throws CurrencyStoreException {
        try (Connection connection = database.connection()) {
            if (readOnly()) connection.setReadOnly(true);
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                T value = work.run(connection); connection.commit(); return value;
            } catch (PurchaseAbort abort) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw abort;
            } catch (Exception ex) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw new CurrencyStoreException(ex);
            } finally {
                try { connection.setAutoCommit(autoCommit); } catch (SQLException ignored) { }
            }
        } catch (PurchaseAbort abort) { throw abort; }
        catch (SQLException ex) { throw new CurrencyStoreException(ex); }
    }

    private DeseModels.Purchase priorPurchase(String interactionId) throws CurrencyStoreException {
        if (!validInteractionId(interactionId)) return null;
        return transaction(connection -> priorPurchase(connection, interactionId));
    }

    private static DeseModels.Purchase priorPurchase(Connection connection, String interactionId) throws SQLException {
        try (var sql = connection.prepareStatement("""
            SELECT quantity,unit_price,amount,balance_before,balance_after,transaction_id
            FROM economy_market_transactions WHERE interaction_id=?
            """)) {
            sql.setString(1, interactionId);
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) return null;
                return new DeseModels.Purchase(DeseModels.PurchaseState.PURCHASED, null, rows.getInt(1),
                    rows.getLong(2), Math.abs(rows.getLong(3)), rows.getLong(4), rows.getLong(5), rows.getString(6));
            }
        }
    }

    private static DeseModels.Product readProduct(ResultSet rows) throws SQLException {
        var tags = new ArrayList<String>();
        var sqlArray = rows.getArray("tags");
        if (sqlArray != null) {
            Object raw = sqlArray.getArray();
            if (raw instanceof Object[] values) for (Object value : values) if (value != null) tags.add(value.toString());
            sqlArray.free();
        }
        return new DeseModels.Product(rows.getString("product_id"), rows.getString("name"),
            rows.getString("description"), rows.getString("category"), rows.getLong("current_price"),
            rows.getLong("base_price"), rows.getLong("minimum_price"), rows.getLong("maximum_price"),
            rows.getDouble("volatility"), rows.getLong("stock"), instant(rows.getTimestamp("created_at")),
            instant(rows.getTimestamp("updated_at")), instant(rows.getTimestamp("next_price_at")),
            rows.getBoolean("available"), rows.getString("rarity"), List.copyOf(tags));
    }

    private static DeseModels.Sale readSale(ResultSet rows) throws SQLException {
        return new DeseModels.Sale(rows.getString("sale_id"), rows.getString("product_id"),
            rows.getString("category"), rows.getInt("discount_percent"), instant(rows.getTimestamp("starts_at")),
            instant(rows.getTimestamp("ends_at")), rows.getString("status"));
    }

    private static void validateProduct(DeseModels.Product product) {
        Objects.requireNonNull(product, "product");
        if (!validProductId(product.id()) || ShopCatalog.find(product.id()) != null
            || product.name() == null || product.name().isBlank() || product.name().length() > 100
            || product.description() == null || product.description().length() > 1000
            || product.category() == null || !product.category().matches("[a-z0-9_-]{1,64}")
            || product.currentPrice() < 1 || product.currentPrice() > 1_000_000_000L
            || product.basePrice() < 1 || product.basePrice() > 1_000_000_000L
            || product.minimumPrice() < 1 || product.maximumPrice() < product.minimumPrice()
            || product.basePrice() < product.minimumPrice() || product.basePrice() > product.maximumPrice()
            || product.currentPrice() < product.minimumPrice() || product.currentPrice() > product.maximumPrice()
            || !Double.isFinite(product.volatility()) || product.volatility() < 0 || product.volatility() > 0.25
            || product.stock() < -1 || product.createdAt() == null || product.updatedAt() == null
            || product.nextPriceAt() == null || product.rarity() == null || !product.rarity().matches("[a-z0-9_-]{1,32}")
            || product.tags().size() > 32 || product.tags().stream().anyMatch(tag -> tag == null || tag.length() > 64))
            throw new IllegalArgumentException("Invalid market product");
    }

    private double sample() {
        double value = random.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value >= 1)
            throw new IllegalArgumentException("Random source must return a value in [0, 1)");
        return value;
    }
    private int randomIndex(int size) { return Math.min(size - 1, (int)(sample() * size)); }
    private static String normalizeId(String id) {
        ShopCatalog.Item legacy = ShopCatalog.find(id);
        return legacy == null ? id == null ? "" : id.strip().toLowerCase(Locale.ROOT) : legacy.id();
    }
    private static boolean validProductId(String value) { return value != null && value.matches("[a-z0-9_-]{1,64}"); }
    private static boolean validSnowflake(String value) { return value != null && value.matches("[0-9]{1,32}"); }
    private static boolean validInteractionId(String value) { return value != null && value.matches("[0-9]{1,64}"); }
    private static Instant instant(Timestamp value) { return value == null ? Instant.EPOCH : value.toInstant(); }
    private static DeseModels.Purchase empty(DeseModels.PurchaseState state, int quantity) {
        return new DeseModels.Purchase(state, null, quantity, 0, 0, 0, 0, "");
    }
    private static DeseModels.Purchase withState(DeseModels.Purchase purchase, DeseModels.PurchaseState state) {
        return new DeseModels.Purchase(state, purchase.product(), purchase.quantity(), purchase.unitPrice(),
            purchase.total(), purchase.balanceBefore(), purchase.balanceAfter(), purchase.transactionId());
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws Exception; }
    private static final class PurchaseAbort extends RuntimeException {
        private final DeseModels.Purchase purchase;
        private PurchaseAbort(DeseModels.Purchase purchase) { this.purchase = purchase; }
    }
}
