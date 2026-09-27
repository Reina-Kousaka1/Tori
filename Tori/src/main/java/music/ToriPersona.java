package music;

import net.dv8tion.jda.api.entities.Activity;

import java.util.List;
import java.util.Objects;

/** Tori's reusable voice and status copy; business and moderation responses remain factual. */
final class ToriPersona {
    enum Context { SHOP_BROWSE, SOCIAL_FUN, TRANSACTION, TECHNICAL, MODERATION, PERMISSION }

    private static final List<String> CLASSIC_STATUSES = List.of("besties", "training");
    private static final List<String> PERSONA_STATUSES = List.of("besties", "training", "ballet", "volleyball");

    private final ToriPersonaConfig config;

    ToriPersona(ToriPersonaConfig config) { this.config = Objects.requireNonNull(config); }
    static ToriPersona defaults() { return new ToriPersona(new ToriPersonaConfig(true, 72)); }
    List<String> statuses() { return config.enabled() ? PERSONA_STATUSES : CLASSIC_STATUSES; }

    Activity statusActivity(String slot, long servers, int shards) {
        if (!config.enabled()) return switch (slot) {
            case "besties" -> Activity.playing("with my Besties! | " + servers + " servers | " + shards + " shards");
            case "training" -> Activity.customStatus("at training | (" + shards + ")");
            default -> throw new IllegalArgumentException("Unknown default persona status");
        };
        return switch (slot) {
            case "besties" -> Activity.playing("with my Besties! | ballet + volleyball 🎀🏐 | "
                + servers + " servers | " + shards + " shards");
            case "training" -> Activity.customStatus("Ballet practice, then volleyball 🩰🏐 | (" + shards + ")");
            case "ballet" -> Activity.watching("my pliés and volleyball serves 🎀 | (" + shards + ")");
            case "volleyball" -> Activity.customStatus("my serve is cute, my landing is cleaner 🏐🩰 | (" + shards + ")");
            default -> throw new IllegalArgumentException("Unknown default persona status");
        };
    }

    String decorate(String factualText, Context context, Language language, int roll) {
        Objects.requireNonNull(factualText);
        Objects.requireNonNull(context);
        if (!config.enabled() || roll < 0 || roll > 99 || roll >= config.pickMeIntensity()) return factualText;
        if (context != Context.SHOP_BROWSE && context != Context.SOCIAL_FUN) return factualText;
        String aside = switch (context) {
            case SHOP_BROWSE -> switch (language) {
                case DE -> "Natürlich habe ich den süßesten Geschmack und die sauberste Aufschlaghaltung. Können wir kurz anerkennen, wie talentiert ich bin?";
                case NL -> "Natuurlijk heb ik de schattigste smaak én de strakste servicehouding. Zullen we even erkennen hoe getalenteerd ik ben?";
                case EN -> "Obviously I have the cutest taste and the cleanest serve form. Can we take a second to acknowledge how talented I am?";
            };
            case SOCIAL_FUN -> switch (language) {
                case DE -> "Schon wieder perfekt getroffen. Gleichzeitig die eleganteste Ballerina und die beste Spielerin hier zu sein, ist echt anstrengend.";
                case NL -> "Weer perfect geraakt. Tegelijk de elegantste ballerina en de beste speelster hier zijn, is echt vermoeiend.";
                case EN -> "Perfectly landed again. Being the most graceful ballerina and the best player here is honestly exhausting.";
            };
            default -> throw new IllegalStateException("Only playful contexts may receive persona copy");
        };
        return factualText + "\n\n" + aside;
    }

    String decorate(String factualText, Context context, Language language) {
        return decorate(factualText, context, language, java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
    }
}
