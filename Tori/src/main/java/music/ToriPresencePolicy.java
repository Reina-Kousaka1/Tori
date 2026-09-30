package music;

import net.dv8tion.jda.api.entities.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Java-owned Discord activity templates resolved from semantic Tori context. */
final class ToriPresencePolicy {
    enum Kind { CUSTOM, PLAYING, WATCHING }
    record Template(Kind kind, String text) {}

    private ToriPresencePolicy() {}

    static List<Template> candidates(ToriPresenceContext context) {
        Objects.requireNonNull(context);
        List<Template> eventChoices = specialEvent(context.specialEvent());
        if (eventChoices == null) {
            String seasonalEvent = switch (context.season()) {
                case HALLOWEEN -> "halloween";
                case CHRISTMAS -> "christmas";
                case VALENTINE -> "valentine";
                default -> null;
            };
            eventChoices = specialEvent(seasonalEvent);
        }
        if (eventChoices != null) return eventChoices;

        var result = new ArrayList<>(base(context.activity()));
        if (context.intensity() >= 0.3) addMood(result, context.activity(), context.mood());
        addSeason(result, context.activity(), context.season());
        return List.copyOf(result);
    }

    static List<String> candidateTexts(ToriPresenceContext context) {
        return candidates(context).stream().map(Template::text).toList();
    }

    static Activity resolve(ToriPresenceContext context, int shardTotal,
                            IntUnaryOperator picker, String previousText) {
        var candidates = candidates(context);
        if (candidates.isEmpty()) candidates = base(ToriPresenceContext.Activity.GENERAL);
        var choices = candidates.stream()
            .filter(template -> !withShard(template.text(), shardTotal).equals(previousText))
            .toList();
        if (choices.isEmpty()) choices = candidates;
        int index = Math.floorMod(picker.applyAsInt(choices.size()), choices.size());
        var selected = choices.get(index);
        String text = withShard(selected.text(), shardTotal);
        return switch (selected.kind()) {
            case PLAYING -> Activity.playing(text);
            case WATCHING -> Activity.watching(text);
            case CUSTOM -> Activity.customStatus(text);
        };
    }

    static String withShard(String text, int shardTotal) {
        return text + " | (" + Math.max(1, shardTotal) + ")";
    }

    private static List<Template> specialEvent(String event) {
        if (event == null) return null;
        return switch (event.toLowerCase(java.util.Locale.ROOT)) {
            case "halloween" -> List.of(custom("Planning the cutest Halloween look 🎃"),
                custom("Spooky season, rehearsal edition 🎀🎃"));
            case "christmas" -> List.of(custom("Making a Christmas wish list ✨🎄"),
                custom("Holiday break after practice 🎄"));
            case "valentine", "valentines" -> List.of(custom("Saving a Valentine for rehearsal 🎀"),
                custom("Serving up a little Valentine energy 🏐💌"));
            case "game_day", "gameday" -> List.of(custom("Game day. Captain mode 🏐"),
                custom("Ready to serve up a win 🏐"));
            case "competition" -> List.of(custom("Competition prep, every detail counts ✨"),
                custom("Ready to own the routine 🎀"));
            default -> null;
        };
    }

    private static List<Template> base(ToriPresenceContext.Activity activity) {
        return switch (activity) {
            case BALLET -> List.of(custom("Practicing at the barre 🎀"),
                custom("Late for rehearsal"), watching("Working on her pirouettes"),
                custom("At ballet practice"), custom("Stretching before class"));
            case VOLLEYBALL -> List.of(custom("Busy at practice 🏐"),
                custom("Volleyball after school"), custom("Working on her serves"),
                custom("Captain mode"), custom("Game day"));
            case CHEER -> List.of(custom("At cheer practice"),
                custom("Competition prep"), custom("Working on the routine"),
                custom("Cheer practice after school"));
            case RESTING -> List.of(custom("Taking a little break"),
                custom("Taking it easy for a bit"), custom("Resting up for tomorrow"));
            case GENERAL, SCHOOL -> List.of(playing("Busy at school 🏐🎀"),
                custom("Doing homework... unfortunately"), custom("Getting ready"),
                custom("Probably shopping"), custom("Taking a little break"));
        };
    }

    private static void addMood(List<Template> choices, ToriPresenceContext.Activity activity,
                                ToriPresenceContext.Mood mood) {
        String text = switch (activity) {
            case BALLET -> switch (mood) {
                case SLEEPY -> "Surviving morning rehearsal 🌙";
                case EXCITED -> "Ready for rehearsal 🎀";
                case FOCUSED -> "Perfecting her pirouettes 🎀";
                case ANNOYED -> "One more plié. Then a break.";
                default -> null;
            };
            case VOLLEYBALL -> switch (mood) {
                case SLEEPY -> "Serving through practice on five minutes of sleep 🏐";
                case COMPETITIVE -> "Captain mode. Let's win this 🏐";
                case EXCITED -> "Ready to serve up a win 🏐";
                case ANNOYED -> "That serve was almost perfect 🏐";
                default -> null;
            };
            case CHEER -> switch (mood) {
                case SLEEPY -> "Running the routine on autopilot 🌙";
                case EXCITED -> "Ready to hit every count 🎀";
                case PLAYFUL, CHAOTIC -> "Adding a little sparkle to the routine ✨";
                case FOCUSED -> "Every count, every detail 🎀";
                default -> null;
            };
            case RESTING -> switch (mood) {
                case SLEEPY -> "Half asleep";
                case ANNOYED -> "Needs five more minutes";
                default -> null;
            };
            case GENERAL, SCHOOL -> switch (mood) {
                case SLEEPY -> "Needs five more minutes";
                case EXCITED -> "Ready for anything ✨";
                case PLAYFUL, CHAOTIC -> "Probably shopping";
                case FOCUSED -> "Getting things done";
                default -> null;
            };
        };
        if (text != null) choices.add(custom(text));
    }

    private static void addSeason(List<Template> choices, ToriPresenceContext.Activity activity,
                                  ToriPresenceContext.Season season) {
        String text = switch (activity) {
            case BALLET -> switch (season) {
                case WINTER -> "Bundled up for ballet rehearsal 🎀❄️";
                case SPRING -> "Spring recital prep 🌷🎀";
                case AUTUMN -> "Rehearsal in the autumn chill 🎀🍂";
                default -> null;
            };
            case VOLLEYBALL -> switch (season) {
                case SUMMER -> "Summer serves after school 🏐☀️";
                case WINTER -> "Indoor practice, winter edition 🏐❄️";
                case AUTUMN -> "Autumn games are here 🏐🍂";
                default -> null;
            };
            case CHEER -> switch (season) {
                case SPRING -> "Spring competition prep 🌷";
                case AUTUMN -> "Cheer prep in crisp fall air 🍂";
                case WINTER -> "Winter routine, same energy ✨";
                default -> null;
            };
            case RESTING, GENERAL, SCHOOL -> switch (season) {
                case SPRING -> "Taking a break in the spring sunshine 🌷";
                case SUMMER -> "Enjoying a little summer break ☀️";
                case AUTUMN -> "Getting cozy this autumn 🍂";
                case WINTER -> "Taking a little winter break ❄️";
                default -> null;
            };
        };
        if (text != null) choices.add(custom(text));
    }

    private static Template custom(String text) { return new Template(Kind.CUSTOM, text); }
    private static Template playing(String text) { return new Template(Kind.PLAYING, text); }
    private static Template watching(String text) { return new Template(Kind.WATCHING, text); }
}
