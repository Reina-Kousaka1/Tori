package music;

import java.util.List;

interface CurrencyStore {
    record Rank(String userId, long balance) {}
    record Grant(ShopCatalog.Item item, int quantity) {}
    record InventoryItem(String id, long quantity) {}
    record Purchase(ShopCatalog.Item item, int quantity, long total, long balance) {}
    record Sale(ShopCatalog.Item item, int quantity, long total, long balance) {}
    record Equipment(String slot, String itemId, int remainingDurability) {}
    record Gather(long credits, String itemId, int remainingDurability, long cooldownRemaining, boolean broke) {}
    record CrateReward(String itemId, long credits) {}

    long balance(String userId) throws CurrencyStoreException;
    long daily(String userId, long now) throws CurrencyStoreException;
    List<InventoryItem> inventory(String userId) throws CurrencyStoreException;
    List<ShopCatalog.Item> shop();
    Purchase buy(String userId, String itemId, int quantity) throws CurrencyStoreException;
    Sale sell(String userId, String itemId, int quantity) throws CurrencyStoreException;
    boolean equip(String userId, String itemId) throws CurrencyStoreException;
    boolean unequip(String userId, String slot) throws CurrencyStoreException;
    List<Equipment> equipment(String userId) throws CurrencyStoreException;
    long beg(String userId, long now, long amount) throws CurrencyStoreException;
    WorkCatalog.Result work(String userId, long now, String jobId) throws CurrencyStoreException;
    boolean transfer(String fromUserId, String toUserId, long amount) throws CurrencyStoreException;
    boolean changeBalance(String userId, long delta) throws CurrencyStoreException;
    boolean settleWager(String userId, long wager, long grossWinnings) throws CurrencyStoreException;
    Grant grantItem(String userId, String itemId, int quantity) throws CurrencyStoreException;
    List<Rank> leaderboard(int limit) throws CurrencyStoreException;
    Gather gather(String userId, String activity, long now) throws CurrencyStoreException;
    boolean craft(String userId, String itemId) throws CurrencyStoreException;
    boolean repair(String userId, String itemId) throws CurrencyStoreException;
    CrateReward openCrate(String userId, String itemId) throws CurrencyStoreException;
}
