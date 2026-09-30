package music;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;

/** Versioned semantic state supplied by Elixir; it contains no Discord-specific activity or copy. */
public record ToriPresenceContext(
    Activity activity,
    Mood mood,
    Season season,
    String specialEvent,
    double intensity,
    long revision,
    Instant updatedAt,
    Instant expiresAt
) {
    public enum Activity { GENERAL, SCHOOL, BALLET, VOLLEYBALL, CHEER, RESTING }
    public enum Mood { NORMAL, HAPPY, EXCITED, PLAYFUL, COMPETITIVE, FOCUSED, SLEEPY, ANNOYED, CHAOTIC }
    public enum Season { SPRING, SUMMER, AUTUMN, WINTER, HALLOWEEN, CHRISTMAS, VALENTINE, NONE }

    public ToriPresenceContext {
        if (activity == null || mood == null || season == null)
            throw new IllegalArgumentException("Presence context enums are required");
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1)
            throw new IllegalArgumentException("Presence intensity must be between zero and one");
        if (revision < 0) throw new IllegalArgumentException("Presence revision must not be negative");
    }

    public static ToriPresenceContext general() {
        return new ToriPresenceContext(Activity.GENERAL, Mood.NORMAL, Season.NONE,
            null, 0, 0, null, null);
    }

    /** Unknown semantic values degrade independently so future Elixir values remain safe. */
    public static ToriPresenceContext fromJson(JsonNode json) {
        JsonNode version = json == null ? null : json.get("schema_version");
        if (json == null || !json.isObject() || version == null
            || !version.isIntegralNumber() || version.asInt() != 2)
            throw new IllegalArgumentException("Unsupported Tori presence context version");

        String activity = requiredText(json, "activity");
        String mood = optionalText(json, "mood", "normal");
        String season = optionalText(json, "season", "none");
        String event = nullableText(json.get("special_event"));
        if (event != null && (event.length() > 64 || !event.matches("[A-Za-z0-9_-]+")))
            throw new IllegalArgumentException("Invalid special event value");

        JsonNode intensityNode = json.get("intensity");
        double intensity = intensityNode == null || intensityNode.isNull() ? 0
            : intensityNode.isNumber() ? intensityNode.asDouble()
            : Double.NaN;
        JsonNode revisionNode = json.get("revision");
        long revision = revisionNode == null || revisionNode.isNull() ? 0
            : revisionNode.isIntegralNumber() ? revisionNode.asLong()
            : -1;
        Instant updatedAt = instant(json.get("updated_at"), "updated_at");
        Instant expiresAt = instant(json.get("expires_at"), "expires_at");

        return new ToriPresenceContext(parseActivity(activity), parseMood(mood), parseSeason(season),
            event, intensity, revision, updatedAt, expiresAt);
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    private static Activity parseActivity(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "school" -> Activity.SCHOOL;
            case "ballet" -> Activity.BALLET;
            case "volleyball" -> Activity.VOLLEYBALL;
            case "cheer" -> Activity.CHEER;
            case "resting" -> Activity.RESTING;
            case "general" -> Activity.GENERAL;
            default -> Activity.GENERAL;
        };
    }

    private static Mood parseMood(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "happy" -> Mood.HAPPY;
            case "excited" -> Mood.EXCITED;
            case "playful" -> Mood.PLAYFUL;
            case "competitive" -> Mood.COMPETITIVE;
            case "focused" -> Mood.FOCUSED;
            case "sleepy" -> Mood.SLEEPY;
            case "annoyed" -> Mood.ANNOYED;
            case "chaotic" -> Mood.CHAOTIC;
            case "normal" -> Mood.NORMAL;
            default -> Mood.NORMAL;
        };
    }

    private static Season parseSeason(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "spring" -> Season.SPRING;
            case "summer" -> Season.SUMMER;
            case "autumn" -> Season.AUTUMN;
            case "winter" -> Season.WINTER;
            case "halloween" -> Season.HALLOWEEN;
            case "christmas" -> Season.CHRISTMAS;
            case "valentine", "valentines" -> Season.VALENTINE;
            case "none" -> Season.NONE;
            default -> Season.NONE;
        };
    }

    private static String requiredText(JsonNode json, String name) {
        JsonNode value = json.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("Missing presence field: " + name);
        return value.asText();
    }

    private static String optionalText(JsonNode json, String name, String fallback) {
        JsonNode value = json.get(name);
        return value == null || value.isNull() ? fallback
            : value.isTextual() ? value.asText() : fallback;
    }

    private static String nullableText(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid special event value");
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Instant instant(JsonNode value, String name) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid presence timestamp: " + name);
        try {
            return OffsetDateTime.parse(value.asText()).toInstant();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid presence timestamp: " + name);
        }
    }
}
