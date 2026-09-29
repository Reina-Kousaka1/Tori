package music;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

/** PostgreSQL-backed shop/game state. Every balance + inventory mutation is transactional. */
final class PostgresCurrencyStore implements CurrencyStore {
    private static final long ACTIVITY_COOLDOWN_MS = 60_000L;
    private static final long GATHER_COOLDOWN_MS = 60_000L;
    private static final List<String> SLOTS = List.of("rod", "pickaxe", "axe", "wrench");
    private final PostgresDatabase database;
    private final RandomGenerator random;

    PostgresCurrencyStore(PostgresDatabase database) { this(database, defaultRandomGenerator()); }

    /** Uses java.base directly instead of discovering an optional RandomGenerator provider. */
    static RandomGenerator defaultRandomGenerator() { return new Random(); }

    PostgresCurrencyStore(PostgresDatabase database, RandomGenerator random) {
        this.database = Objects.requireNonNull(database);
        this.random = Objects.requireNonNull(random);
    }

    @Override public long balance(String userId) throws CurrencyStoreException {
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            return lockedBalance(connection, userId);
        });
    }

    /** Returns zero after claiming 150 credits; otherwise returns milliseconds until the next claim. */
    @Override public long daily(String userId, long now) throws CurrencyStoreException {
        validateUser(userId);
        if (now < 0) throw new IllegalArgumentException("now cannot be negative");
        return transaction(connection -> {
            ensureAccount(connection, userId);
            lockedBalance(connection, userId);
            long last = lastAt(connection, userId, "last_daily_at");
            long wait = DailyCooldown.remainingMillis(now, last);
            if (wait > 0) return wait;
            credit(connection, userId, 150);
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET last_daily_at=?, updated_at=? WHERE user_id=?")) {
                sql.setLong(1, now); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.executeUpdate();
            }
            return 0L;
        });
    }

    @Override public long beg(String userId, long now, long amount) throws CurrencyStoreException {
        return awardOnCooldown(userId, now, amount, "last_beg_at");
    }

    @Override public WorkCatalog.Result work(String userId, long now, String jobId) throws CurrencyStoreException {
        WorkCatalog.Job job = WorkCatalog.find(jobId);
        if (job == null) return new WorkCatalog.Result("", "", 0, 0);
        long amount = WorkCatalog.reward(job);
        long remaining = awardOnCooldown(userId, now, amount, "last_work_at");
        return new WorkCatalog.Result(job.id(), job.name(), remaining == 0 ? amount : 0, remaining);
    }

    private long awardOnCooldown(String userId, long now, long amount, String field) throws CurrencyStoreException {
        validateUser(userId);
        if (now < 0 || amount < 1) throw new IllegalArgumentException("Invalid activity reward");
        if (!Set.of("last_beg_at", "last_work_at").contains(field)) throw new IllegalArgumentException("Invalid activity field");
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long balance = lockedBalance(connection, userId);
            long last = lastAt(connection, userId, field);
            long wait = last == 0 ? 0 : remaining(now, last, ACTIVITY_COOLDOWN_MS);
            if (wait > 0) return wait;
            Math.addExact(balance, amount);
            credit(connection, userId, amount);
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET " + field + "=?,updated_at=? WHERE user_id=?")) {
                sql.setLong(1, now); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.executeUpdate();
            }
            return 0L;
        });
    }

    @Override public List<ShopCatalog.Item> shop() { return ShopCatalog.market(); }

    @Override public List<InventoryItem> inventory(String userId) throws CurrencyStoreException {
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            var result = new ArrayList<InventoryItem>();
            try (var sql = connection.prepareStatement("SELECT item_id, quantity FROM economy_inventory WHERE user_id=? AND quantity>0 ORDER BY item_id")) {
                sql.setString(1, userId);
                try (var rows = sql.executeQuery()) {
                    while (rows.next()) result.add(new InventoryItem(rows.getString(1), rows.getLong(2)));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override public Purchase buy(String userId, String itemId, int quantity) throws CurrencyStoreException {
        ShopCatalog.Item item = ShopCatalog.find(itemId);
        if (item == null || !item.buyable() || quantity < 1 || quantity > 100) return null;
        final long total;
        try { total = Math.multiplyExact(item.price(), quantity); }
        catch (ArithmeticException ex) { return null; }
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long current = lockedBalance(connection, userId);
            if (current < total) return null;
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=balance-?, updated_at=? WHERE user_id=? AND balance>=?")) {
                sql.setLong(1, total); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.setLong(4, total);
                if (sql.executeUpdate() != 1) return null;
            }
            addItem(connection, userId, item.id(), quantity);
            return new Purchase(item, quantity, total, current - total);
        });
    }

    @Override public Sale sell(String userId, String itemId, int quantity) throws CurrencyStoreException {
        ShopCatalog.Item item = ShopCatalog.find(itemId);
        if (item == null || !item.sellable() || quantity < 1 || quantity > 100) return null;
        final long total;
        try { total = Math.multiplyExact(item.sellPrice(), quantity); }
        catch (ArithmeticException ex) { return null; }
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long current = lockedBalance(connection, userId);
            long owned = quantity(connection, userId, item.id());
            if (owned < quantity || isEquipped(connection, userId, item.id()) && owned <= quantity) return null;
            removeItem(connection, userId, item.id(), quantity);
            long next = Math.addExact(current, total);
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=?, updated_at=? WHERE user_id=?")) {
                sql.setLong(1, next); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.executeUpdate();
            }
            return new Sale(item, quantity, total, next);
        });
    }

    @Override public boolean equip(String userId, String itemId) throws CurrencyStoreException {
        ShopCatalog.Item item = ShopCatalog.find(itemId);
        if (item == null || !item.tool()) return false;
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            lockedBalance(connection, userId);
            if (quantity(connection, userId, item.id()) < 1 || wear(connection, userId, item.id()) >= item.durability()) return false;
            try (var sql = connection.prepareStatement("""
                INSERT INTO economy_equipment(user_id,slot,item_id) VALUES (?,?,?)
                ON CONFLICT(user_id,slot) DO UPDATE SET item_id=EXCLUDED.item_id
                """)) {
                sql.setString(1, userId); sql.setString(2, item.toolSlot()); sql.setString(3, item.id()); sql.executeUpdate();
            }
            return true;
        });
    }

    @Override public boolean unequip(String userId, String slot) throws CurrencyStoreException {
        if (!SLOTS.contains(slot)) return false;
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            try (var sql = connection.prepareStatement("DELETE FROM economy_equipment WHERE user_id=? AND slot=?")) {
                sql.setString(1, userId); sql.setString(2, slot);
                return sql.executeUpdate() == 1;
            }
        });
    }

    @Override public boolean transfer(String fromUserId, String toUserId, long amount) throws CurrencyStoreException {
        if (fromUserId == null || toUserId == null || fromUserId.equals(toUserId) || amount < 1) return false;
        validateUser(fromUserId); validateUser(toUserId);
        return transaction(connection -> {
            ensureAccount(connection, fromUserId); ensureAccount(connection, toUserId);
            String first = fromUserId.compareTo(toUserId) < 0 ? fromUserId : toUserId;
            String second = fromUserId.equals(first) ? toUserId : fromUserId;
            long firstBalance = lockedBalance(connection, first);
            long secondBalance = lockedBalance(connection, second);
            long fromBalance = fromUserId.equals(first) ? firstBalance : secondBalance;
            long toBalance = toUserId.equals(first) ? firstBalance : secondBalance;
            if (fromBalance < amount) return false;
            final long nextTo;
            try { nextTo = Math.addExact(toBalance, amount); }
            catch (ArithmeticException ex) { return false; }
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=?,updated_at=? WHERE user_id=?")) {
                sql.setLong(1, fromBalance - amount); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, fromUserId);
                if (sql.executeUpdate() != 1) return false;
            }
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=?,updated_at=? WHERE user_id=?")) {
                sql.setLong(1, nextTo); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, toUserId);
                if (sql.executeUpdate() != 1) throw new SQLException("Transfer recipient disappeared");
            }
            return true;
        });
    }

    @Override public boolean changeBalance(String userId, long delta) throws CurrencyStoreException {
        validateUser(userId);
        if (delta == 0) return true;
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long balance = lockedBalance(connection, userId);
            final long updated;
            try { updated = Math.addExact(balance, delta); }
            catch (ArithmeticException ex) { return false; }
            if (updated < 0) return false;
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=?,updated_at=? WHERE user_id=?")) {
                sql.setLong(1, updated); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId);
                return sql.executeUpdate() == 1;
            }
        });
    }

    /** Debits the wager and credits the gross return atomically; the caller supplies only a policy result. */
    @Override public boolean settleWager(String userId, long wager, long grossWinnings) throws CurrencyStoreException {
        validateUser(userId);
        if (wager < 1 || grossWinnings < 0) return false;
        long maximum = wager > Long.MAX_VALUE / 3 ? Long.MAX_VALUE : wager * 3;
        if (grossWinnings > maximum) return false;
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long balance = lockedBalance(connection, userId);
            if (balance < wager) return false;
            final long after;
            try { after = Math.addExact(balance - wager, grossWinnings); }
            catch (ArithmeticException ex) { return false; }
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=?,updated_at=? WHERE user_id=? AND balance>=?")) {
                sql.setLong(1, after); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.setLong(4, wager);
                return sql.executeUpdate() == 1;
            }
        });
    }

    @Override public Grant grantItem(String userId, String itemId, int quantity) throws CurrencyStoreException {
        ShopCatalog.Item item = ShopCatalog.find(itemId);
        if (item == null || quantity < 1 || quantity > 100) return null;
        validateUser(userId);
        transaction(connection -> {
            ensureAccount(connection, userId);
            addItem(connection, userId, item.id(), quantity);
            return null;
        });
        return new Grant(item, quantity);
    }

    @Override public List<Rank> leaderboard(int limit) throws CurrencyStoreException {
        int count = Math.clamp(limit, 1, 20);
        return transaction(connection -> {
            var result = new ArrayList<Rank>();
            try (var sql = connection.prepareStatement("SELECT user_id,balance FROM economy_accounts ORDER BY balance DESC,user_id LIMIT ?")) {
                sql.setInt(1, count);
                try (var rows = sql.executeQuery()) {
                    while (rows.next()) result.add(new Rank(rows.getString(1), rows.getLong(2)));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override public List<Equipment> equipment(String userId) throws CurrencyStoreException {
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            var result = new ArrayList<Equipment>();
            try (var sql = connection.prepareStatement("SELECT slot,item_id FROM economy_equipment WHERE user_id=? ORDER BY slot")) {
                sql.setString(1, userId);
                try (var rows = sql.executeQuery()) {
                    while (rows.next()) {
                        String slot = rows.getString(1), id = rows.getString(2);
                        ShopCatalog.Item item = ShopCatalog.find(id);
                        if (item != null) result.add(new Equipment(slot, id, Math.max(0, item.durability() - wear(connection, userId, id))));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override public Gather gather(String userId, String activity, long now) throws CurrencyStoreException {
        String slot = switch (activity) { case "fish" -> "rod"; case "mine" -> "pickaxe"; case "chop" -> "axe"; default -> null; };
        if (slot == null) return null;
        validateUser(userId);
        if (now < 0) throw new IllegalArgumentException("now cannot be negative");
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long balance = lockedBalance(connection, userId);
            String itemId = equippedItem(connection, userId, slot);
            ShopCatalog.Item tool = ShopCatalog.find(itemId);
            if (tool == null || !slot.equals(tool.toolSlot()) || quantity(connection, userId, itemId) < 1) return null;
            long last = lastAt(connection, userId, "last_" + activity + "_at");
            long wait = last == 0 ? 0 : remaining(now, last, GATHER_COOLDOWN_MS);
            if (wait > 0) return new Gather(0, "", Math.max(0, tool.durability() - wear(connection, userId, itemId)), wait, false);
            int used = wear(connection, userId, itemId);
            if (used >= tool.durability()) return null;
            int tier = MiningFishingPolicy.tier(tool.durability());
            long credits = random.nextLong(10L * tier, 26L * tier);
            String drop = MiningFishingPolicy.drop(activity, tier, random.nextInt(100));
            Math.addExact(balance, credits);
            credit(connection, userId, credits);
            addItem(connection, userId, drop, 1);
            setWear(connection, userId, itemId, used + 1);
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET last_" + activity + "_at=?, updated_at=? WHERE user_id=?")) {
                sql.setLong(1, now); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.executeUpdate();
            }
            boolean broke = used + 1 >= tool.durability();
            if (broke) try (var sql = connection.prepareStatement("DELETE FROM economy_equipment WHERE user_id=? AND slot=? AND item_id=?")) {
                sql.setString(1, userId); sql.setString(2, slot); sql.setString(3, itemId); sql.executeUpdate();
            }
            return new Gather(credits, drop, broke ? 0 : tool.durability() - used - 1, 0, broke);
        });
    }

    @Override public boolean craft(String userId, String itemId) throws CurrencyStoreException {
        ShopCatalog.Recipe recipe = ShopCatalog.recipe(itemId);
        if (recipe == null) return false;
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            lockedBalance(connection, userId);
            if (equippedItem(connection, userId, "wrench") == null) return false;
            for (var entry : recipe.ingredients().entrySet()) {
                ShopCatalog.Item ingredient = ShopCatalog.find(entry.getKey());
                long owned = quantity(connection, userId, entry.getKey());
                if (owned < entry.getValue()) return false;
                if (ingredient != null && ingredient.tool() && isEquipped(connection, userId, ingredient.id())
                    && owned <= entry.getValue()) return false;
            }
            for (var entry : recipe.ingredients().entrySet()) removeItem(connection, userId, entry.getKey(), entry.getValue());
            addItem(connection, userId, recipe.output().id(), 1);
            return true;
        });
    }

    @Override public boolean repair(String userId, String itemId) throws CurrencyStoreException {
        ShopCatalog.Item item = ShopCatalog.find(itemId);
        if (item == null || !item.tool()) return false;
        validateUser(userId);
        long cost = Math.max(10, item.sellPrice() / 2);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long balance = lockedBalance(connection, userId);
            if (quantity(connection, userId, item.id()) < 1 || equippedItem(connection, userId, "wrench") == null
                || wear(connection, userId, item.id()) < 1 || balance < cost) return false;
            try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=balance-?,updated_at=? WHERE user_id=? AND balance>=?")) {
                sql.setLong(1, cost); PostgresTimestamps.bind(sql, 2, Instant.now());
                sql.setString(3, userId); sql.setLong(4, cost);
                if (sql.executeUpdate() != 1) return false;
            }
            setWear(connection, userId, item.id(), 0);
            return true;
        });
    }

    @Override public CrateReward openCrate(String userId, String itemId) throws CurrencyStoreException {
        ShopCatalog.Item crate = ShopCatalog.find(itemId);
        if (crate == null || !Set.of("fish_crate", "mine_crate", "chop_crate").contains(crate.id())) return null;
        validateUser(userId);
        return transaction(connection -> {
            ensureAccount(connection, userId);
            long balance = lockedBalance(connection, userId);
            if (quantity(connection, userId, crate.id()) < 1 || quantity(connection, userId, "crate_key") < 1) return null;
            String[] pool = switch (crate.id()) {
                case "fish_crate" -> new String[] {"fish", "tropical_fish", "shell"};
                case "mine_crate" -> new String[] {"rock", "cobweb", "gem_fragment"};
                default -> new String[] {"wood", "apple", "gem_fragment"};
            };
            String reward = pool[random.nextInt(pool.length)];
            long credits = random.nextLong(25, 76);
            Math.addExact(balance, credits);
            removeItem(connection, userId, crate.id(), 1);
            removeItem(connection, userId, "crate_key", 1);
            addItem(connection, userId, reward, 1);
            credit(connection, userId, credits);
            return new CrateReward(reward, credits);
        });
    }

    private <T> T transaction(SqlWork<T> work) throws CurrencyStoreException {
        try (Connection connection = database.connection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception ex) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw new CurrencyStoreException(ex);
            } finally {
                try { connection.setAutoCommit(autoCommit); } catch (SQLException ignored) { }
            }
        } catch (SQLException ex) { throw new CurrencyStoreException(ex); }
    }

    private static void ensureAccount(Connection connection, String userId) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO economy_accounts(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING")) {
            sql.setString(1, userId); sql.executeUpdate();
        }
    }

    private static long lockedBalance(Connection connection, String userId) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT balance FROM economy_accounts WHERE user_id=? FOR UPDATE")) {
            sql.setString(1, userId);
            try (var rows = sql.executeQuery()) { if (!rows.next()) throw new SQLException(); return rows.getLong(1); }
        }
    }

    private static long lastAt(Connection connection, String userId, String field) throws SQLException {
        if (!Set.of("last_daily_at", "last_fish_at", "last_mine_at", "last_chop_at", "last_beg_at", "last_work_at").contains(field)) throw new IllegalArgumentException();
        try (var sql = connection.prepareStatement("SELECT " + field + " FROM economy_accounts WHERE user_id=?")) {
            sql.setString(1, userId); try (var rows = sql.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private static long quantity(Connection connection, String userId, String itemId) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT quantity FROM economy_inventory WHERE user_id=? AND item_id=? FOR UPDATE")) {
            sql.setString(1, userId); sql.setString(2, itemId);
            try (var rows = sql.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }

    private static void addItem(Connection connection, String userId, String itemId, long amount) throws SQLException {
        try (var sql = connection.prepareStatement("""
            INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES (?,?,?)
            ON CONFLICT(user_id,item_id) DO UPDATE SET quantity=economy_inventory.quantity+EXCLUDED.quantity
            """)) {
            sql.setString(1, userId); sql.setString(2, itemId); sql.setLong(3, amount); sql.executeUpdate();
        }
    }

    private static void removeItem(Connection connection, String userId, String itemId, long amount) throws SQLException {
        int removed;
        try (var sql = connection.prepareStatement("DELETE FROM economy_inventory WHERE user_id=? AND item_id=? AND quantity=?")) {
            sql.setString(1, userId); sql.setString(2, itemId); sql.setLong(3, amount);
            removed = sql.executeUpdate();
        }
        if (removed == 0) {
            try (var sql = connection.prepareStatement("UPDATE economy_inventory SET quantity=quantity-? WHERE user_id=? AND item_id=? AND quantity>?")) {
                sql.setLong(1, amount); sql.setString(2, userId); sql.setString(3, itemId); sql.setLong(4, amount);
                if (sql.executeUpdate() != 1) throw new SQLException("Insufficient item quantity");
            }
        }
        try (var sql = connection.prepareStatement("""
            DELETE FROM economy_tool_wear WHERE user_id=? AND item_id=?
            AND NOT EXISTS (SELECT 1 FROM economy_inventory WHERE user_id=? AND item_id=?)
            AND NOT EXISTS (SELECT 1 FROM economy_equipment WHERE user_id=? AND item_id=?)
            """)) {
            sql.setString(1, userId); sql.setString(2, itemId);
            sql.setString(3, userId); sql.setString(4, itemId);
            sql.setString(5, userId); sql.setString(6, itemId); sql.executeUpdate();
        }
    }

    private static boolean isEquipped(Connection connection, String userId, String itemId) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT 1 FROM economy_equipment WHERE user_id=? AND item_id=?")) {
            sql.setString(1, userId); sql.setString(2, itemId); try (var rows = sql.executeQuery()) { return rows.next(); }
        }
    }

    private static String equippedItem(Connection connection, String userId, String slot) throws SQLException {
        if (!SLOTS.contains(slot)) throw new IllegalArgumentException("Invalid equipment slot");
        try (var sql = connection.prepareStatement("SELECT item_id FROM economy_equipment WHERE user_id=? AND slot=? FOR UPDATE")) {
            sql.setString(1, userId); sql.setString(2, slot); try (var rows = sql.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static int wear(Connection connection, String userId, String itemId) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT used FROM economy_tool_wear WHERE user_id=? AND item_id=? FOR UPDATE")) {
            sql.setString(1, userId); sql.setString(2, itemId); try (var rows = sql.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }

    private static void setWear(Connection connection, String userId, String itemId, int used) throws SQLException {
        try (var sql = connection.prepareStatement("""
            INSERT INTO economy_tool_wear(user_id,item_id,used) VALUES (?,?,?)
            ON CONFLICT(user_id,item_id) DO UPDATE SET used=EXCLUDED.used
            """)) {
            sql.setString(1, userId); sql.setString(2, itemId); sql.setInt(3, used); sql.executeUpdate();
        }
    }

    private static void credit(Connection connection, String userId, long amount) throws SQLException {
        try (var sql = connection.prepareStatement("UPDATE economy_accounts SET balance=balance+?,updated_at=? WHERE user_id=?")) {
            sql.setLong(1, amount); PostgresTimestamps.bind(sql, 2, Instant.now());
            sql.setString(3, userId); sql.executeUpdate();
        }
    }

    private static long remaining(long now, long last, long cooldown) {
        if (now < last) return cooldown;
        return Math.max(0, cooldown - (now - last));
    }

    private static void validateUser(String userId) {
        if (userId == null || !userId.matches("[0-9]{1,32}")) throw new IllegalArgumentException("Invalid user ID");
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws Exception; }
}
