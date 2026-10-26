package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small configurable HTTPS GIF pool. Empty configuration renders text without an image. */
final class SocialGifPool implements SocialGifProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_URLS_PER_ACTION = 30;
    private final Map<String, List<URI>> urls;

    private SocialGifPool(Map<String, List<URI>> urls) {
        this.urls = Map.copyOf(urls);
    }

    static SocialGifPool empty() { return new SocialGifPool(Map.of()); }

    static SocialGifPool fromJson(String configuration) {
        if (configuration == null || configuration.isBlank()) return empty();
        try {
            JsonNode root = JSON.readTree(configuration);
            if (root == null || !root.isObject()) throw invalidConfiguration();

            Map<String, List<URI>> parsed = new HashMap<>();
            var fields = root.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!SocialActionCommands.contains(field.getKey()) || !field.getValue().isArray()
                    || field.getValue().size() == 0 || field.getValue().size() > MAX_URLS_PER_ACTION)
                    throw invalidConfiguration();

                List<URI> actionUrls = new ArrayList<>();
                for (JsonNode entry : field.getValue()) {
                    if (!entry.isTextual() || entry.asText().length() > 2048) throw invalidConfiguration();
                    URI uri = URI.create(entry.asText());
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                        throw invalidConfiguration();
                    actionUrls.add(uri);
                }
                parsed.put(field.getKey(), List.copyOf(actionUrls));
            }
            return new SocialGifPool(parsed);
        } catch (Exception ex) {
            throw invalidConfiguration();
        }
    }

    @Override
    public Optional<URI> select(String actionId, String interactionId) {
        List<URI> candidates = urls.get(actionId);
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        int index = Math.floorMod(interactionId.hashCode(), candidates.size());
        return Optional.of(candidates.get(index));
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException(
            "TORI_SOCIAL_GIF_URLS must map social action IDs to HTTPS URL arrays without credentials or query strings.");
    }
}
