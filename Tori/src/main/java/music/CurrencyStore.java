package music;

import java.util.List;

interface CurrencyStore {
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
    List<Equipment> equipment(String userId) throws CurrencyStoreException;
    Gather gather(String userId, String activity, long now) throws CurrencyStoreException;
    boolean craft(String userId, String itemId) throws CurrencyStoreException;
    boolean repair(String userId, String itemId) throws CurrencyStoreException;
    CrateReward openCrate(String userId, String itemId) throws CurrencyStoreException;
}
