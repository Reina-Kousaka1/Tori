package music;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

/** Current Tori market and the already-defined fishing/mining progression. */
final class ShopCatalog {
    record Item(String id, String name, long price, long sellPrice, String category,
                String description, String toolSlot, int durability) {
        boolean buyable() { return price > 0; }
        boolean sellable() { return sellPrice > 0; }
        boolean tool() { return !toolSlot.isEmpty(); }
    }
    record Recipe(Item output, Map<String, Integer> ingredients) {}

    private static final Map<String, Item> ITEMS;
    private static final Map<String, Map<String, Integer>> RECIPES = Map.ofEntries(
        Map.entry("comet_rod", Map.of("wood", 2, "gem_fragment", 3, "fish", 2)),
        Map.entry("star_rod", Map.of("comet_rod", 1, "diamond", 2, "gem_fragment", 5)),
        Map.entry("moon_rod", Map.of("star_rod", 1, "moon_rune", 2, "gem_fragment", 8)),
        Map.entry("sparkle_rod", Map.of("moon_rod", 1, "sparkle_fragment", 3, "gem_fragment", 10)),
        Map.entry("hellfire_rod", Map.of("sparkle_rod", 1, "diamond", 20, "sparkle_fragment", 20)),
        Map.entry("comet_pickaxe", Map.of("wood", 2, "gem_fragment", 3, "rock", 5)),
        Map.entry("star_pickaxe", Map.of("comet_pickaxe", 1, "diamond", 2, "gem_fragment", 5)),
        Map.entry("shooting_star_pickaxe", Map.of("comet_pickaxe", 1, "diamond", 3, "gem_fragment", 8)),
        Map.entry("diamond_pickaxe", Map.of("shooting_star_pickaxe", 1, "diamond", 5, "gem_fragment", 12)),
        Map.entry("moon_pickaxe", Map.of("star_pickaxe", 1, "moon_rune", 2, "gem_fragment", 8)),
        Map.entry("sparkle_pickaxe", Map.of("moon_pickaxe", 1, "sparkle_fragment", 3, "gem_fragment", 10)),
        Map.entry("hellfire_pickaxe", Map.of("sparkle_pickaxe", 1, "diamond", 20, "sparkle_fragment", 20)),
        Map.entry("comet_axe", Map.of("wood", 5, "gem_fragment", 3, "rock", 2)),
        Map.entry("star_axe", Map.of("comet_axe", 1, "diamond", 2, "gem_fragment", 5)),
        Map.entry("moon_axe", Map.of("star_axe", 1, "moon_rune", 2, "gem_fragment", 8)),
        Map.entry("sparkle_axe", Map.of("moon_axe", 1, "sparkle_fragment", 3, "gem_fragment", 10)),
        Map.entry("hellfire_axe", Map.of("sparkle_axe", 1, "diamond", 20, "sparkle_fragment", 20))
    );

    static {
        var items = new LinkedHashMap<String, Item>();
        add(items, "cookie", "Cookie", 10, 5, "common", "A sweet snack.", "", 0);
        add(items, "chocolate", "Chocolate", 23, 11, "common", "A gift or a snack.", "", 0);
        add(items, "coffee", "Coffee", 10, 5, "common", "A warm drink.", "", 0);
        add(items, "milk", "Glass of Milk", 25, 12, "common", "A glass of milk.", "", 0);
        add(items, "rose", "Rose", 25, 12, "common", "A flower for someone special.", "", 0);
        add(items, "ring", "Marriage Ring", 60, 30, "common", "A ring for your collection.", "", 0);
        add(items, "necklace", "Necklace", 17, 8, "common", "A small accessory.", "", 0);
        add(items, "shoes", "Shoes", 10, 5, "common", "A pair of shoes.", "", 0);
        add(items, "clothes", "Clothes", 30, 15, "common", "An outfit.", "", 0);
        add(items, "dress", "Wedding Dress", 75, 37, "common", "A wedding outfit.", "", 0);
        add(items, "tuxedo", "Tuxedo", 50, 25, "common", "A formal outfit.", "", 0);
        add(items, "diamond", "Diamond", 200, 100, "common", "A valuable gem.", "", 0);
        add(items, "car", "Car", 300, 150, "common", "A car for your collection.", "", 0);
        add(items, "house", "House", 750, 375, "common", "A home for your collection.", "", 0);
        add(items, "slot_ticket", "Slot Ticket", 65, 32, "common", "A ticket for the slots.", "", 0);
        add(items, "crate_key", "Crate Key", 58, 29, "common", "Opens a found crate.", "", 0);
        add(items, "fishing_bait", "Fishing Bait", 15, 7, "tools", "Bait for fishing.", "", 0);
        add(items, "fishing_rod", "Fishing Rod", 65, 32, "tools", "A basic fishing rod.", "rod", 40);
        add(items, "brom_pickaxe", "Brom's Pickaxe", 100, 50, "tools", "A basic mining pickaxe.", "pickaxe", 40);
        add(items, "axe", "Axe", 100, 50, "tools", "A basic woodcutting axe.", "axe", 35);
        add(items, "wrench", "Wrench", 50, 25, "tools", "A basic crafting wrench.", "wrench", 35);
        add(items, "comet_rod", "Comet Rod", 0, 75, "rare", "A crafted fishing rod.", "rod", 130);
        add(items, "star_rod", "Star Rod", 0, 125, "rare", "A crafted fishing rod.", "rod", 170);
        add(items, "moon_rod", "Moon Rod", 0, 400, "rare", "A rare fishing rod.", "rod", 200);
        add(items, "sparkle_rod", "Sparkle Rod", 0, 400, "rare", "A sparkling fishing rod.", "rod", 300);
        add(items, "hellfire_rod", "Hellfire Rod", 0, 7500, "rare", "A top-tier fishing rod.", "rod", 2500);
        add(items, "comet_pickaxe", "Comet Pickaxe", 0, 145, "rare", "A crafted mining pickaxe.", "pickaxe", 180);
        add(items, "star_pickaxe", "Star Pickaxe", 0, 175, "rare", "A crafted mining pickaxe.", "pickaxe", 220);
        add(items, "shooting_star_pickaxe", "Shooting Star Pickaxe", 0, 260, "rare", "An upgraded crafted mining pickaxe.", "pickaxe", 260);
        add(items, "diamond_pickaxe", "Diamond Pickaxe", 0, 500, "rare", "A high-tier diamond mining pickaxe.", "pickaxe", 420);
        add(items, "moon_pickaxe", "Moon Pickaxe", 0, 500, "rare", "A rare mining pickaxe.", "pickaxe", 320);
        add(items, "sparkle_pickaxe", "Sparkle Pickaxe", 0, 600, "rare", "A sparkling mining pickaxe.", "pickaxe", 450);
        add(items, "hellfire_pickaxe", "Hellfire Pickaxe", 0, 7500, "rare", "A top-tier mining pickaxe.", "pickaxe", 3000);
        add(items, "comet_axe", "Comet Axe", 0, 145, "rare", "A crafted woodcutting axe.", "axe", 170);
        add(items, "star_axe", "Star Axe", 0, 175, "rare", "A crafted woodcutting axe.", "axe", 220);
        add(items, "moon_axe", "Moon Axe", 0, 500, "rare", "A rare woodcutting axe.", "axe", 350);
        add(items, "sparkle_axe", "Sparkle Axe", 0, 600, "rare", "A sparkling woodcutting axe.", "axe", 500);
        add(items, "hellfire_axe", "Hellfire Axe", 0, 7500, "rare", "A top-tier woodcutting axe.", "axe", 3100);
        add(items, "fish", "Fish", 0, 10, "drops", "A common catch.", "", 0);
        add(items, "tropical_fish", "Tropical Fish", 0, 30, "drops", "A colorful catch.", "", 0);
        add(items, "blowfish", "Blowfish", 0, 15, "drops", "A prickly catch.", "", 0);
        add(items, "shell", "Shell", 0, 800, "drops", "A rare seaside find.", "", 0);
        add(items, "shark", "Shark", 0, 1000, "drops", "A very rare catch.", "", 0);
        add(items, "crab", "Crab", 0, 40, "drops", "A seaside catch.", "", 0);
        add(items, "squid", "Squid", 0, 45, "drops", "A deep-water catch.", "", 0);
        add(items, "rock", "Rock", 0, 5, "drops", "A common mining find.", "", 0);
        add(items, "cobweb", "Cobweb", 0, 10, "drops", "A mining material.", "", 0);
        add(items, "gem_fragment", "Gem Fragment", 0, 50, "drops", "A crafting material.", "", 0);
        add(items, "moon_rune", "Moon Rune", 0, 100, "drops", "A rare crafting material.", "", 0);
        add(items, "sparkle_fragment", "Sparkle Fragment", 0, 605, "drops", "A rare crafting material.", "", 0);
        add(items, "wood", "Wood", 0, 10, "drops", "A common woodcutting find.", "", 0);
        add(items, "apple", "Apple", 0, 15, "drops", "A fruit from a tree.", "", 0);
        add(items, "pear", "Pear", 0, 15, "drops", "A fruit from a tree.", "", 0);
        add(items, "leaves", "Leaves", 0, 5, "drops", "A woodcutting material.", "", 0);
        add(items, "fish_crate", "Fish Treasure", 0, 100, "drops", "A rare fishing treasure.", "", 0);
        add(items, "mine_crate", "Gem Crate", 0, 100, "drops", "A rare mining treasure.", "", 0);
        add(items, "chop_crate", "Chop Crate", 0, 100, "drops", "A rare woodcutting treasure.", "", 0);
        add(items, "lucky_charm", "Lucky Charm", 500, 250, "collectibles", "A lucky charm.", "", 0);
        add(items, "trophy", "Trophy", 2500, 1250, "collectibles", "A collector's trophy.", "", 0);
        add(items, "crown", "Crown", 10000, 5000, "collectibles", "A premium collectible.", "", 0);
        ITEMS = Collections.unmodifiableMap(items);
    }

    private ShopCatalog() {}
    private static void add(Map<String, Item> items, String id, String name, long price, long sellPrice,
                            String category, String description, String slot, int durability) {
        items.put(id, new Item(id, name, price, sellPrice, category, description, slot, durability));
    }

    static List<Item> market() { return ITEMS.values().stream().filter(Item::buyable).toList(); }
    static Item find(String id) {
        if (id == null) return null;
        String key = id.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return ITEMS.get(key);
    }
    static Recipe recipe(String id) {
        Item output = find(id);
        Map<String, Integer> parts = output == null ? null : RECIPES.get(output.id());
        return parts == null ? null : new Recipe(output, parts);
    }
    static List<Recipe> recipes() { return RECIPES.keySet().stream().sorted().map(ShopCatalog::recipe).toList(); }
}
