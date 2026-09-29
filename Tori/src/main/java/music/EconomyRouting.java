package music;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Central opt-in routing for the Economy API's read paths. */
final class EconomyRouting {
    enum Source { LEGACY, ELIXIR }

    private static final Set<String> AREAS = Set.of("balance", "inventory", "shop", "leaderboard");
    private final Map<String, Source> sources;

    private EconomyRouting(Map<String, Source> sources) { this.sources = Map.copyOf(sources); }

    static EconomyRouting from(BotConfig config) {
        var routes = defaults();
        String configured = config.get("TORI_ECONOMY_ROUTING");
        if (configured == null || configured.isBlank()) {
            // Backward compatibility for the first balance-only rollout switch.
            routes.put("balance", parseSource(config.get("TORI_ECONOMY_BALANCE_SOURCE", "LEGACY"), "balance"));
        } else {
            var seen = new HashSet<String>();
            for (String entry : configured.split(",")) {
                String[] pair = entry.strip().split("=", -1);
                if (pair.length != 2 || !AREAS.contains(pair[0].strip().toLowerCase(Locale.ROOT)))
                    throw new BotConfig.ConfigurationException("TORI_ECONOMY_ROUTING contains an invalid area entry.");
                String area = pair[0].strip().toLowerCase(Locale.ROOT);
                if (!seen.add(area))
                    throw new BotConfig.ConfigurationException("TORI_ECONOMY_ROUTING contains a duplicate area.");
                routes.put(area, parseSource(pair[1], area));
            }
        }
        return new EconomyRouting(routes);
    }

    static EconomyRouting legacy() { return new EconomyRouting(defaults()); }

    Source source(String area) { return sources.getOrDefault(area, Source.LEGACY); }
    boolean usesElixir() { return sources.containsValue(Source.ELIXIR); }
    Map<String, Source> snapshot() { return sources; }

    private static LinkedHashMap<String, Source> defaults() {
        var routes = new LinkedHashMap<String, Source>();
        AREAS.stream().sorted().forEach(area -> routes.put(area, Source.LEGACY));
        return routes;
    }

    private static Source parseSource(String raw, String area) {
        try { return Source.valueOf(raw.strip().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) {
            throw new BotConfig.ConfigurationException("Economy route for " + area + " must be LEGACY or ELIXIR.");
        }
    }
}
